import { ChangeDetectorRef, Component, OnDestroy, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { CandidateJobSummary, CandidateJobDetail, CandidateJobFitResult, CandidateJobPageResponse, ResumeHealthScore } from '@airral/shared-types';
import { catchError, finalize, of, retry, Subscription, timeout } from 'rxjs';
import { getOnboardingJobSearchSeed, OnboardingJobSearchSeed } from '../../utils/job-search-seed';

interface JobDescriptionSection {
  title: string;
  body?: string;
  items: string[];
}

interface JobDescriptionView {
  quickRead: string;
  sections: JobDescriptionSection[];
  originalText: string;
  hasContent: boolean;
}

@Component({
  selector: 'app-jobs',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './jobs.component.html',
  styleUrl: './jobs.component.css',
})
export class JobsComponent implements OnInit, OnDestroy {
  /**
   * Long enough to survive an API cold start.
   *
   * <p>The API runs at min-instances 0, so the first request after a quiet
   * period waits for a Spring Boot start -- measured at about twelve seconds,
   * longer when the database has just woken. Fifteen seconds was therefore
   * guaranteed to fail on the visit that matters most: now that this page is
   * public, a first-time visitor's opening impression could be an error on the
   * one page whose purpose is to show what the product does.
   */
  private readonly jobsTimeoutMs = 30000;
  private readonly detailTimeoutMs = 12000;
  private readonly searchDebounceMs = 350;
  private readonly detailCache = new Map<string, CandidateJobDetail>();
  private jobsRequestId = 0;
  private jobsRequest?: Subscription;
  private searchDebounceTimer?: ReturnType<typeof setTimeout>;

  jobs: CandidateJobSummary[] = [];
  selectedJob: CandidateJobDetail | null = null;
  selectedJobId: string | null = null;
  loading = false;
  loadingMore = false;
  loadingDetail = false;
  detailError = false;
  jobsError = '';
  savingJob = false;
  fittingJob = false;
  actionMessage = '';
  actionError = '';
  fitResult: CandidateJobFitResult | null = null;
  descriptionView: JobDescriptionView = this.emptyDescriptionView();
  savedJobKeys = new Set<string>();
  searchQuery = '';
  onboardingStartPending = false;
  onboardingSearchSeed: OnboardingJobSearchSeed | null = null;
  hasMore = false;
  offset = 0;
  limit = 50;

  // Filters
  filterWorkMode: 'all' | 'REMOTE' | 'HYBRID' | 'ONSITE' = 'all';
  filterMaxAgeDays: number | undefined = undefined;
  filterSalaryPosted = false;
  filterVisaFriendly = false;
  filterExperience: 'all' | 'entry' | 'mid' | 'senior' | 'staff' = 'all';
  filtersExpanded = true;

  // Resume health banner
  resumeHealth: ResumeHealthScore | null = null;
  resumeHealthDismissed = false;

  // "What's new" tracking
  newSinceLastVisit = 0;

  // Mobile detail view state
  mobileDetailOpen = false;

  constructor(
    private readonly candidateApi: CandidatePortalService,
    private readonly route: ActivatedRoute,
    private readonly auth: AuthService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.filtersExpanded = !this.isMobileViewport();
    this.preparePostOnboardingSearch();
    if (this.onboardingStartPending) {
      return;
    }

    this.signedIn.set(this.auth.isAuthenticated());
    this.loadJobs();
    this.loadResumeHealth();
    this.checkProfileUpdate();
  }

  ngOnDestroy(): void {
    this.jobsRequest?.unsubscribe();
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
    }
  }

  /**
   * If the user updated their profile since last visiting jobs,
   * clear cached results and show a notice that matches are fresh.
   */
  private checkProfileUpdate(): void {
    const profileUpdatedAt = localStorage.getItem('airral_profile_updated');
    const lastJobsLoad = localStorage.getItem('airral_last_jobs_visit');

    if (profileUpdatedAt && lastJobsLoad) {
      const profileTime = parseInt(profileUpdatedAt, 10);
      const jobsTime = new Date(lastJobsLoad).getTime();
      if (profileTime > jobsTime) {
        // Profile was updated after last jobs visit — matches are now re-ranked
        this.actionMessage = 'Your job matches updated based on your new profile.';
        setTimeout(() => (this.actionMessage = ''), 5000);
      }
    }
    // Clear the flag so it doesn't show again on next visit
    localStorage.removeItem('airral_profile_updated');
  }

  private loadResumeHealth(): void {
    // Silent. Nobody asked for this, so an anonymous visitor should not be
    // interrupted by it -- it simply does not load.
    if (!this.signedIn()) {
      return;
    }
    this.candidateApi.getResumeHealth().pipe(
      catchError(() => of(null))
    ).subscribe((health) => {
      this.resumeHealth = health;
      this.changeDetectorRef.detectChanges();
    });
  }

  dismissResumeHealthBanner(): void {
    this.resumeHealthDismissed = true;
  }

  closeMobileDetail(): void {
    this.mobileDetailOpen = false;
  }

  get showResumeHealthBanner(): boolean {
    return !this.resumeHealthDismissed
      && this.resumeHealth !== null
      && this.resumeHealth.score < 75
      && !this.onboardingStartPending;
  }

  get resumeHealthTopFix(): string {
    return this.resumeHealth?.topFixes?.[0] ?? '';
  }

  loadJobs(): void {
    const requestId = ++this.jobsRequestId;
    this.jobsRequest?.unsubscribe();
    this.loading = true;
    this.loadingMore = false;
    this.jobsError = '';
    this.jobsRequest = this.candidateApi
      .getRecommendedJobsPage(
        this.limit,
        this.offset,
        undefined,
        this.searchQuery || undefined,
        this.filterMaxAgeDays,
        this.filterWorkMode !== 'all' ? this.filterWorkMode : undefined,
        this.filterSalaryPosted || undefined,
        this.filterExperience !== 'all' ? this.filterExperience : undefined,
        this.filterVisaFriendly || undefined
      )
      .pipe(
        timeout(this.jobsTimeoutMs),
        // One retry, because the failure mode is nearly always a cold start:
        // the first request wakes the API and the second finds it warm --
        // measured at 196ms once running. This turns a dead end into a slow
        // first load. Only once, so a genuine outage still surfaces rather
        // than hammering a service that is already down.
        retry({ count: 1, delay: 1000 }),
        catchError(() => {
          if (requestId === this.jobsRequestId) {
            this.jobsError = 'Still waking up. Give it a moment, or refresh the search.';
          }
          return of(null);
        }),
        finalize(() => {
          if (requestId === this.jobsRequestId) {
            this.loading = false;
            this.changeDetectorRef.detectChanges();
          }
        })
      )
      .subscribe({
        next: (response) => {
          if (requestId !== this.jobsRequestId || !response) {
            return;
          }

          const page = this.normalizeJobPage(response);
          this.jobs = page.jobs;
          this.hasMore = page.hasMore;
          this.computeNewSinceLastVisit(this.jobs);
          this.reconcileSelectedJob();
          this.changeDetectorRef.detectChanges();
        },
        error: () => {
          if (requestId !== this.jobsRequestId) {
            return;
          }
          this.jobsError = 'Could not load jobs. Try refreshing the search.';
          this.changeDetectorRef.detectChanges();
        },
      });
  }

  selectJob(job: CandidateJobSummary): void {
    const jobKey = this.getJobKey(job);
    if (this.selectedJobId === jobKey) {
      this.mobileDetailOpen = true;
      return;
    }

    this.selectedJobId = jobKey;
    this.selectedJob = this.detailCache.get(jobKey) ?? { ...job };
    this.descriptionView = this.buildDescriptionView(this.selectedJob);
    this.detailError = false;
    this.actionMessage = '';
    this.actionError = '';
    this.fitResult = null;
    this.mobileDetailOpen = true;

    if (this.detailCache.has(jobKey)) {
      this.loadingDetail = false;
      return;
    }

    if (!job.sourceType || !job.sourceBoardToken || !job.externalJobId) {
      this.loadingDetail = false;
      this.detailError = true;
      return;
    }

    this.loadingDetail = true;
    this.candidateApi
      .getExternalJobDetail(job.sourceType!, job.sourceBoardToken!, job.externalJobId!)
      .pipe(
        timeout(this.detailTimeoutMs),
        catchError(() => of(null))
      )
      .subscribe((detail) => {
        if (this.selectedJobId !== jobKey) {
          return;
        }

        this.loadingDetail = false;
        if (!detail) {
          this.detailError = true;
          this.changeDetectorRef.detectChanges();
          return;
        }

        const hydratedDetail = {
          ...job,
          ...detail,
          matchReasons: detail.matchReasons?.length ? detail.matchReasons : job.matchReasons,
        };
        this.detailCache.set(jobKey, hydratedDetail);
        this.jobs = this.jobs.map((existingJob) => this.getJobKey(existingJob) === jobKey ? { ...existingJob, ...hydratedDetail } : existingJob);
        this.selectedJob = hydratedDetail;
        this.descriptionView = this.buildDescriptionView(hydratedDetail);
        this.changeDetectorRef.detectChanges();
      });
  }

  onSearch(): void {
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
      this.searchDebounceTimer = undefined;
    }
    this.onboardingStartPending = false;
    this.offset = 0;
    this.loadJobs();
  }

  onSearchInput(query: string): void {
    this.searchQuery = query;
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
    }
    this.searchDebounceTimer = setTimeout(() => this.onSearch(), this.searchDebounceMs);
  }

  clearSearch(): void {
    this.searchQuery = '';
    this.onSearch();
  }

  toggleFilters(): void {
    this.filtersExpanded = !this.filtersExpanded;
  }

  setWorkModeFilter(mode: 'all' | 'REMOTE' | 'HYBRID' | 'ONSITE'): void {
    if (this.filterWorkMode === mode) return;
    this.filterWorkMode = mode;
    this.applyFilters();
  }

  setFreshnessFilter(days: number | undefined): void {
    if (this.filterMaxAgeDays === days) return;
    this.filterMaxAgeDays = days;
    this.applyFilters();
  }

  toggleSalaryFilter(): void {
    this.filterSalaryPosted = !this.filterSalaryPosted;
    this.applyFilters();
  }

  toggleVisaFilter(): void {
    this.filterVisaFriendly = !this.filterVisaFriendly;
    this.applyFilters();
  }

  setExperienceFilter(level: 'all' | 'entry' | 'mid' | 'senior' | 'staff'): void {
    if (this.filterExperience === level) return;
    this.filterExperience = level;
    this.applyFilters();
  }

  get hasActiveFilters(): boolean {
    return this.filterWorkMode !== 'all' || this.filterMaxAgeDays !== undefined || this.filterSalaryPosted || this.filterVisaFriendly || this.filterExperience !== 'all';
  }

  get activeFilterCount(): number {
    return [
      this.filterWorkMode !== 'all',
      this.filterMaxAgeDays !== undefined,
      this.filterSalaryPosted,
      this.filterVisaFriendly,
      this.filterExperience !== 'all',
    ].filter(Boolean).length;
  }

  clearFilters(): void {
    this.filterWorkMode = 'all';
    this.filterMaxAgeDays = undefined;
    this.filterSalaryPosted = false;
    this.filterVisaFriendly = false;
    this.filterExperience = 'all';
    this.applyFilters();
  }

  private applyFilters(): void {
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
      this.searchDebounceTimer = undefined;
    }
    this.offset = 0;
    this.loadJobs();
  }

  loadMore(): void {
    if (!this.hasMore || this.loading || this.loadingMore) return;
    const requestId = ++this.jobsRequestId;
    this.jobsRequest?.unsubscribe();
    const requestedOffset = this.offset + this.limit;
    this.loadingMore = true;
    this.jobsError = '';
    this.jobsRequest = this.candidateApi
      .getRecommendedJobsPage(
        this.limit,
        requestedOffset,
        undefined,
        this.searchQuery || undefined,
        this.filterMaxAgeDays,
        this.filterWorkMode !== 'all' ? this.filterWorkMode : undefined,
        this.filterSalaryPosted || undefined,
        this.filterExperience !== 'all' ? this.filterExperience : undefined,
        this.filterVisaFriendly || undefined
      )
      .pipe(
        timeout(this.jobsTimeoutMs),
        catchError(() => {
          if (requestId === this.jobsRequestId) {
            this.jobsError = 'Could not load more jobs. Try again.';
          }
          return of(null);
        }),
        finalize(() => {
          if (requestId === this.jobsRequestId) {
            this.loadingMore = false;
            this.changeDetectorRef.detectChanges();
          }
        })
      )
      .subscribe({
        next: (response) => {
          if (requestId !== this.jobsRequestId || !response) {
            return;
          }

          const page = this.normalizeJobPage(response);
          this.jobs = [...this.jobs, ...page.jobs];
          this.offset = page.offset;
          this.hasMore = page.hasMore;
          this.changeDetectorRef.detectChanges();
        },
        error: () => {
          if (requestId !== this.jobsRequestId) {
            return;
          }
          this.jobsError = 'Could not load more jobs. Try again.';
          this.changeDetectorRef.detectChanges();
        },
      });
  }

  isSelected(job: CandidateJobSummary): boolean {
    return this.selectedJobId === this.getJobKey(job);
  }

  readonly trackByJob = (_index: number, job: CandidateJobSummary): string => this.getJobKey(job);

  startSearch(): void {
    this.onboardingStartPending = false;
    const seededQuery = this.onboardingSearchSeed?.query?.trim();
    if (seededQuery && !this.searchQuery.trim()) {
      this.searchQuery = seededQuery;
    }
    this.loadJobs();
  }

  saveSelectedJob(): void {
    if (!this.requireAccount('save this job so it is still here when you come back')) {
      return;
    }
    const sourceJobKey = this.getSelectedSourceJobKey();
    if (!sourceJobKey || this.savingJob) {
      return;
    }

    this.savingJob = true;
    this.actionMessage = '';
    this.actionError = '';

    this.candidateApi.saveCandidateJob({
      sourceJobKey,
      status: 'SAVED',
      nextStep: 'Check resume fit',
    }).subscribe({
      next: () => {
        this.savedJobKeys.add(sourceJobKey);
        this.savingJob = false;
        this.actionMessage = 'Saved to your tracker.';
      },
      error: () => {
        this.savingJob = false;
        this.actionError = 'Could not save this job. Try again in a moment.';
      },
    });
  }

  runFitForSelectedJob(): void {
    if (!this.requireAccount('check your resume against this job')) {
      return;
    }
    const sourceJobKey = this.getSelectedSourceJobKey();
    if (!sourceJobKey || this.fittingJob) {
      return;
    }

    this.fittingJob = true;
    this.fitResult = null;
    this.actionMessage = '';
    this.actionError = '';

    this.candidateApi.runJobFit({ sourceJobKey }).subscribe({
      next: (result) => {
        this.savedJobKeys.add(sourceJobKey);
        this.fitResult = result;
        this.fittingJob = false;
        this.actionMessage = 'Resume fit is ready.';
      },
      error: () => {
        this.fittingJob = false;
        this.actionError = 'Upload a resume first, then run fit for this job.';
      },
    });
  }

  isSelectedJobSaved(): boolean {
    const sourceJobKey = this.getSelectedSourceJobKey();
    return Boolean(sourceJobKey && this.savedJobKeys.has(sourceJobKey));
  }

  private preparePostOnboardingSearch(): void {
    if (this.route.snapshot.queryParamMap.get('from') !== 'onboarding') {
      return;
    }

    this.onboardingSearchSeed = getOnboardingJobSearchSeed(this.auth.getCurrentUser()?.email);
    this.searchQuery = this.onboardingSearchSeed?.query ?? '';
    this.onboardingStartPending = true;
  }

  /**
   * Whether the visitor has an account.
   *
   * <p>This page is reachable without one, deliberately: searching and reading
   * a posting need no credential, and the analysis panel is the only thing that
   * shows what AIRRAL actually does. Asking people to sign up before seeing it
   * meant asking them to trust the product sight unseen -- and the corpus is
   * public data regardless, since the same search answers without a credential.
   *
   * <p>Saving, resume fit and resume health do need an account, and each asks
   * at the moment it is used, where the reason is self-evident.
   */
  readonly signedIn = signal(false);

  /** Set when an anonymous visitor reaches for something that needs an account. */
  readonly signUpPrompt = signal<string>('');

  /**
   * Ask for an account, naming what they were trying to do.
   *
   * <p>A bare "sign in to continue" gives no reason to. Naming the feature
   * turns an interruption into an explanation.
   */
  private requireAccount(reason: string): boolean {
    if (this.signedIn()) {
      return true;
    }
    this.signUpPrompt.set(reason);
    return false;
  }

  dismissSignUpPrompt(): void {
    this.signUpPrompt.set('');
  }

  /** Carries the current search back, so signing up does not lose their place. */
  signUpUrl(): string {
    const back = encodeURIComponent(
      `${window.location.pathname}${window.location.search}`
    );
    return `/login?mode=register&returnUrl=${back}`;
  }

  private getJobKey(job: Pick<CandidateJobSummary, 'sourceType' | 'sourceBoardToken' | 'externalJobId' | 'jobId'>): string {
    return job.jobId || `${job.sourceType || 'source'}/${job.sourceBoardToken || 'board'}/${job.externalJobId || 'job'}`;
  }

  private getSelectedSourceJobKey(): string | null {
    return this.selectedJob ? this.getSourceJobKey(this.selectedJob) : null;
  }

  private getSourceJobKey(job: Pick<CandidateJobSummary, 'sourceType' | 'sourceBoardToken' | 'externalJobId' | 'jobId'>): string | null {
    if (job.jobId?.includes(':')) {
      return job.jobId;
    }

    if (!job.sourceType || !job.sourceBoardToken || !job.externalJobId) {
      return null;
    }

    return `${job.sourceType.toLowerCase()}:${job.sourceBoardToken}:${job.externalJobId}`;
  }

  private normalizeJobPage(response: CandidateJobPageResponse | CandidateJobSummary[] | null | undefined): CandidateJobPageResponse {
    if (Array.isArray(response)) {
      return {
        jobs: response,
        limit: this.limit,
        offset: this.offset,
        hasMore: false,
      };
    }

    return {
      jobs: Array.isArray(response?.jobs) ? response.jobs : [],
      limit: response?.limit ?? this.limit,
      offset: response?.offset ?? this.offset,
      hasMore: response?.hasMore ?? false,
      nextOffset: response?.nextOffset,
    };
  }

  private reconcileSelectedJob(): void {
    if (this.jobs.length === 0) {
      this.clearSelectedJob();
      return;
    }

    const selectedSummary = this.selectedJobId
      ? this.jobs.find((job) => this.getJobKey(job) === this.selectedJobId)
      : undefined;

    if (selectedSummary && this.selectedJob) {
      this.selectedJob = { ...this.selectedJob, ...selectedSummary };
      this.descriptionView = this.buildDescriptionView(this.selectedJob);
      return;
    }

    this.clearSelectedJob();
    this.selectJob(this.jobs[0]);
    if (this.isMobileViewport()) {
      this.mobileDetailOpen = false;
    }
  }

  private clearSelectedJob(): void {
    this.selectedJob = null;
    this.selectedJobId = null;
    this.descriptionView = this.emptyDescriptionView();
    this.detailError = false;
    this.fitResult = null;
    this.loadingDetail = false;
  }

  private isMobileViewport(): boolean {
    return typeof window !== 'undefined' && window.innerWidth <= 768;
  }

  getTimeAgo(date: string | undefined): string {
    if (!date) return '';
    const now = new Date();
    const posted = new Date(date);
    const days = Math.floor((now.getTime() - posted.getTime()) / (1000 * 60 * 60 * 24));
    if (days === 0) return 'Today';
    if (days === 1) return '1d ago';
    if (days < 7) return `${days}d ago`;
    if (days < 30) return `${Math.floor(days / 7)}w ago`;
    return `${Math.floor(days / 30)}mo ago`;
  }

  getPostedLabel(job: CandidateJobSummary): string {
    return job.postedLabel || this.getTimeAgo(job.sourceUpdatedAt) || 'Recent';
  }

  getSalaryLabel(job: CandidateJobSummary): string {
    return job.salaryLabel || 'Salary not listed';
  }

  hasPostedSalary(job: CandidateJobSummary): boolean {
    const salary = job.salaryLabel?.trim().toLowerCase();
    return Boolean(
      salary
      && !salary.includes('not listed')
      && !salary.includes('benchmark needed')
      && salary !== 'n/a'
    );
  }

  getExperienceLabel(job: CandidateJobSummary): string {
    if (job.experienceYears !== null && job.experienceYears !== undefined) {
      return `${job.experienceYears}+ yr`;
    }
    if (job.seniorityLabel) {
      return job.seniorityLabel;
    }
    return '';
  }

  getLocationLabel(job: CandidateJobSummary): string {
    return this.compactLocation(job.location) || 'Location not listed';
  }

  getWorkModeLabel(job: CandidateJobSummary): string {
    if (!job.workMode || job.workMode === 'UNKNOWN') {
      return '';
    }

    return String(job.workMode).toLowerCase().replace(/^\w/, (letter) => letter.toUpperCase());
  }

  getSourceSignal(job: CandidateJobSummary): string {
    if (job.qualityReasons?.some((reason) => reason.toLowerCase().includes('direct apply'))) {
      return 'Direct apply';
    }

    if (job.sourceName) {
      return `${job.sourceName} source`;
    }

    return '';
  }

  getVisaSignal(job: CandidateJobSummary): string {
    const lang = job.sponsorshipLanguage;
    if (!lang || lang === 'UNKNOWN') {
      return '';
    }
    if (lang === 'SPONSORS') {
      return 'Sponsors visa';
    }
    if (lang === 'NO_SPONSORSHIP') {
      return 'No sponsorship';
    }
    return '';
  }

  /**
   * Whether a match percentage means anything for this viewer.
   *
   * <p>The score is computed for everyone, including signed-out visitors who
   * have no profile to match against -- so the detail panel was labelling a
   * title-keyword guess as a "profile match" to people who had not given us a
   * profile. A number with nothing behind it is worse than no number.
   */
  showMatchScore(job: CandidateJobSummary | null): boolean {
    return Boolean(job?.matchScore && this.signedIn());
  }

  /**
   * Says what an active filter is hiding, and how to see it again.
   *
   * <p>The work-mode and experience filters exclude postings that state neither,
   * which is a large slice of the corpus -- most employers publish no work-mode
   * field, and a substantial share never name a level. Excluding them is what
   * makes the filter mean anything, but doing it silently would just move the
   * dishonesty: the user would believe they had seen everything matching.
   */
  filterCaveat(): string {
    const modeOn = this.filterWorkMode && this.filterWorkMode !== 'all';
    const levelOn = this.filterExperience && this.filterExperience !== 'all';

    if (modeOn && levelOn) {
      return 'Postings that state no work mode or no experience level are hidden. '
        + 'Set either back to All to include them.';
    }
    if (modeOn) {
      return 'Many employers never state a work mode. Those postings are hidden while this filter is on — choose All to include them.';
    }
    if (levelOn) {
      return 'Postings that do not state an experience level are hidden while this filter is on — choose Any level to include them.';
    }
    return '';
  }

  /**
   * The sponsorship lines only, for the panel that quotes the posting.
   *
   * <p>visaReasons also carries a contract/staffing note, which is a different
   * concern and is driven by a bare substring test on the word "contract" -- it
   * fires on Contracts Manager and Vendor Operations roles. It has its own home
   * in the caution list, so it is kept out of a panel headed "what this posting
   * says about sponsorship", where it would read as an immigration finding and
   * carry more authority than it has earned.
   */
  getSponsorshipNotes(job: CandidateJobSummary | null): string[] {
    return (job?.visaReasons ?? []).filter((reason) => !/contract|staffing/i.test(reason));
  }

  /**
   * Where the pay figure came from.
   *
   * <p>Worth saying out loud because it is now sometimes true: the sync used to
   * stamp every posting "Salary not listed" regardless of what the employer
   * published, so there was no provenance to report. Now that the employer's own
   * range is read from the source, "employer posted" distinguishes a figure the
   * company stands behind from one we could not find -- which is the difference
   * a candidate actually cares about.
   */
  getPayProvenance(job: CandidateJobSummary | null): string {
    if (!job || !this.hasPostedSalary(job)) {
      return '';
    }

    return job.compensationConfidence === 'POSTED_BASE' ? 'Employer posted' : '';
  }

  getDecisionLabel(job: CandidateJobSummary | null): string {
    const tier = this.getDecisionTier(job);
    if (tier === 'apply') {
      return 'Apply first';
    }
    if (tier === 'skip') {
      return 'Check risk';
    }
    return 'Review';
  }

  getDecisionClass(job: CandidateJobSummary | null): string {
    return `decision-${this.getDecisionTier(job)}`;
  }

  getApplyReasons(job: CandidateJobSummary | null): string[] {
    if (!job) {
      return [];
    }

    const fit = this.getFitForJob(job);
    const reasons = [
      ...(fit ? [`Resume fit: ${fit.fitScore}%`] : []),
      ...(fit?.matchedRequirements?.length ? [`Resume matches ${fit.matchedRequirements.slice(0, 3).join(', ')}`] : []),
      ...(job.matchReasons ?? []).filter((reason) => !this.isCautionReasonText(reason)),
      ...(job.jobQualityScore && job.jobQualityScore >= 90 ? ['High job-quality score'] : []),
      ...(this.hasPostedSalary(job) ? ['Employer salary is visible'] : []),
      ...(this.isOfficialSource(job) ? ['Official or direct source'] : []),
      ...(this.isFreshJob(job) ? ['Fresh posting'] : []),
      ...(job.easyApplyAvailable ? ['Low-friction application'] : []),
    ];

    return this.unique(reasons).slice(0, 5);
  }

  getCautionReasons(job: CandidateJobSummary | null): string[] {
    if (!job) {
      return [];
    }

    const reasons: string[] = [];
    const matchScore = job.matchScore ?? 0;
    const postedDaysAgo = this.getPostedDaysAgo(job.sourceUpdatedAt);
    const sponsorshipLanguage = String(job.sponsorshipLanguage || '').toUpperCase();

    reasons.push(...(job.matchReasons ?? []).filter((reason) => this.isCautionReasonText(reason)).slice(0, 2));
    const fit = this.getFitForJob(job);
    if (fit?.keywordGaps?.length) {
      reasons.push(`Resume keyword gaps: ${fit.keywordGaps.slice(0, 2).join(', ')}`);
    } else if (fit?.missingRequirements?.length) {
      reasons.push(`Missing requirements: ${fit.missingRequirements.slice(0, 2).join(', ')}`);
    }

    if (!this.hasPostedSalary(job)) {
      reasons.push('Salary is not listed');
    }
    // Only a real profile can produce a weak one. Signed out there is nothing to
    // match against, so this told visitors their profile was letting them down
    // before they had given us a profile at all.
    if (this.showMatchScore(job) && matchScore < 72) {
      reasons.push('Profile match is weaker than your best options');
    }
    if (postedDaysAgo !== null && postedDaysAgo > 30) {
      reasons.push('Posting may be getting stale');
    }
    if (sponsorshipLanguage === 'NO_SPONSORSHIP') {
      reasons.push('Posting says no sponsorship');
    }
    if (job.contractOrStaffingRisk) {
      reasons.push('Contract or staffing risk detected');
    }
    if (!job.location) {
      reasons.push('Location is unclear');
    }

    return reasons.slice(0, 5);
  }

  getResumeNextMove(job: CandidateJobSummary | null): string {
    if (!job) {
      return 'Select a job to see the next best action.';
    }

    if (this.fitResult) {
      if (this.fitResult.keywordGaps.length) {
        return `Fix resume keywords: ${this.fitResult.keywordGaps.slice(0, 2).join(', ')}.`;
      }
      if (this.fitResult.missingRequirements.length) {
        return `Close requirement gaps: ${this.fitResult.missingRequirements.slice(0, 2).join(', ')}.`;
      }
      return 'Resume fit is ready. Save the job and apply while it is fresh.';
    }

    if ((job.matchScore ?? 0) >= 82) {
      return 'Run resume fit before applying so AIRRAL can catch missing keywords.';
    }

    return 'Review the caution list before spending time on this application.';
  }

  getFitChecklist(): string[] {
    if (this.fitResult?.applicationChecklist?.length) {
      return this.fitResult.applicationChecklist.slice(0, 5);
    }

    return [
      'Run resume fit for this exact job',
      'Save the job before opening the external application',
      'Check salary, work mode, and source quality',
    ];
  }

  copyJobReport(): void {
    if (!this.selectedJob) {
      return;
    }

    const report = [
      `${this.selectedJob.title} at ${this.selectedJob.companyName}`,
      `${this.getDecisionLabel(this.selectedJob)} · ${this.selectedJob.matchScore ?? 'No'}% match`,
      ...(this.fitResult ? [`Resume fit: ${this.fitResult.fitScore}%`] : []),
      '',
      'Why apply:',
      ...this.getApplyReasons(this.selectedJob).map((reason) => `- ${reason}`),
      '',
      'Check before applying:',
      ...this.getCautionReasons(this.selectedJob).map((reason) => `- ${reason}`),
      '',
      `Next move: ${this.getResumeNextMove(this.selectedJob)}`,
    ].filter(Boolean).join('\n');

    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(report).then(() => {
        this.actionMessage = 'Job report copied.';
        this.actionError = '';
        this.changeDetectorRef.detectChanges();
      }).catch(() => {
        this.actionError = 'Could not copy the report. Try again.';
        this.actionMessage = '';
        this.changeDetectorRef.detectChanges();
      });
      return;
    }

    this.actionError = 'Copy is not available in this browser.';
  }

  getMatchReasons(job: CandidateJobSummary | null): string[] {
    return (job?.matchReasons ?? []).slice(0, 5);
  }

  private getFitForJob(job: CandidateJobSummary | null): CandidateJobFitResult | null {
    if (!job || !this.fitResult || !this.selectedJob) {
      return null;
    }

    return this.getJobKey(job) === this.getJobKey(this.selectedJob) ? this.fitResult : null;
  }

  private getDecisionTier(job: CandidateJobSummary | null): 'apply' | 'review' | 'skip' {
    if (!job) {
      return 'review';
    }

    // The score only carries a verdict when it was computed against a profile.
    // Signed out it is a title-keyword guess that lands around 64, which tripped
    // the skip threshold below -- so a posting with employer-posted pay, stated
    // sponsorship, a fresh date and no caution flags was stamped "Check risk"
    // while the panel beside it read "No major caution flags". With no profile
    // the tier is decided by the cautions alone, which are statements of fact.
    const hasProfileMatch = this.showMatchScore(job);
    const score = job.matchScore ?? 0;
    const cautions = this.getCautionReasons(job).length;
    const qualityScore = job.jobQualityScore ?? 0;

    if (hasProfileMatch && score >= 82 && cautions <= 1) {
      return 'apply';
    }
    if (hasProfileMatch && score >= 78 && qualityScore >= 90 && cautions <= 2) {
      return 'apply';
    }
    if ((hasProfileMatch && score > 0 && score < 68) || cautions >= 4) {
      return 'skip';
    }
    return 'review';
  }

  private isOfficialSource(job: CandidateJobSummary): boolean {
    const source = `${job.sourceName || ''} ${job.sourceType || ''}`.toLowerCase();
    const reasons = (job.qualityReasons ?? []).join(' ').toLowerCase();
    return Boolean(
      source.includes('greenhouse') ||
      source.includes('lever') ||
      source.includes('ashby') ||
      source.includes('smartrecruiters') ||
      reasons.includes('direct apply') ||
      reasons.includes('official') ||
      reasons.includes('fresh source')
    );
  }

  private isFreshJob(job: CandidateJobSummary): boolean {
    const postedDaysAgo = this.getPostedDaysAgo(job.sourceUpdatedAt);
    if (postedDaysAgo === null) {
      return String(job.postedLabel || '').toLowerCase().includes('today') || String(job.postedLabel || '').toLowerCase().includes('updated');
    }

    return postedDaysAgo <= 14;
  }

  private isCautionReasonText(reason: string): boolean {
    const value = reason.toLowerCase();
    return (
      value.includes('may not') ||
      value.includes('not fit') ||
      value.includes('missing') ||
      value.includes('gap') ||
      value.includes('weak') ||
      value.includes('outside') ||
      value.includes('target titles') ||
      value.includes('unclear') ||
      value.includes('risk') ||
      value.includes('no sponsorship') ||
      value.includes('requires')
    );
  }

  private getPostedDaysAgo(date: string | undefined): number | null {
    if (!date) {
      return null;
    }

    const posted = new Date(date);
    if (Number.isNaN(posted.getTime())) {
      return null;
    }

    const now = new Date();
    return Math.max(0, Math.floor((now.getTime() - posted.getTime()) / (1000 * 60 * 60 * 24)));
  }

  private buildDescriptionView(job: CandidateJobDetail | null): JobDescriptionView {
    if (!job) {
      return this.emptyDescriptionView();
    }

    const html = this.getDecodedDescriptionHtml(job.descriptionHtml);
    const parsed = html ? this.parseHtmlDescription(html) : this.emptyDescriptionView();
    const textFallback = this.cleanText(job.descriptionText || job.descriptionExcerpt || '');
    const originalText = parsed.originalText || textFallback;

    if (!originalText) {
      return this.emptyDescriptionView();
    }

    const quickRead = this.firstMeaningfulText(
      parsed.quickRead,
      this.cleanText(job.descriptionExcerpt || ''),
      textFallback
    );
    const sections = parsed.sections.length ? parsed.sections : this.sectionsFromPlainText(originalText);

    return {
      quickRead: this.trimToSentences(quickRead || originalText, 2),
      sections,
      originalText,
      hasContent: true,
    };
  }

  private parseHtmlDescription(html: string): JobDescriptionView {
    if (typeof DOMParser === 'undefined') {
      const originalText = this.cleanText(this.stripHtml(html));
      return {
        quickRead: this.trimToSentences(originalText, 2),
        sections: this.sectionsFromPlainText(originalText),
        originalText,
        hasContent: Boolean(originalText),
      };
    }

    const documentRef = new DOMParser().parseFromString(html, 'text/html');
    documentRef.querySelectorAll('script, style, noscript').forEach((node) => node.remove());
    const body = documentRef.body;
    const originalText = this.cleanText(body.textContent || '');
    const firstParagraph = Array.from(body.querySelectorAll('p'))
      .map((node) => this.cleanText(node.textContent || ''))
      .find((text) => text.length > 40) || '';
    const sections = this.extractHtmlSections(body);
    const paySection = this.extractPaySection(body);

    if (paySection) {
      const existingPayIndex = sections.findIndex((section) => section.title === paySection.title);
      if (existingPayIndex >= 0) {
        sections[existingPayIndex] = {
          ...sections[existingPayIndex],
          items: [...paySection.items, ...sections[existingPayIndex].items].slice(0, 8),
        };
      } else {
        sections.splice(Math.min(2, sections.length), 0, paySection);
      }
    }

    return {
      quickRead: this.trimToSentences(firstParagraph || originalText, 2),
      sections,
      originalText,
      hasContent: Boolean(originalText),
    };
  }

  private extractHtmlSections(root: HTMLElement): JobDescriptionSection[] {
    const sections: JobDescriptionSection[] = [];
    const headings = Array.from(root.querySelectorAll('h1, h2, h3, h4'));

    for (const heading of headings) {
      const rawTitle = this.cleanText(heading.textContent || '');
      const title = this.mapSectionTitle(rawTitle);
      if (!title) {
        continue;
      }

      const bodyParts: string[] = [];
      const items: string[] = [];
      let next = heading.nextElementSibling;

      while (next && !/^H[1-4]$/.test(next.tagName)) {
        if (next.matches('ul, ol')) {
          items.push(...Array.from(next.querySelectorAll('li')).map((item) => this.cleanText(item.textContent || '')).filter(Boolean));
        } else {
          const text = this.cleanText(next.textContent || '');
          if (text && text.length > 20) {
            bodyParts.push(text);
          }
        }
        next = next.nextElementSibling;
      }

      const dedupedItems = this.unique(items).slice(0, 8);
      const body = this.trimToSentences(bodyParts.join(' '), 2);
      if (body || dedupedItems.length) {
        sections.push({
          title,
          body,
          items: dedupedItems,
        });
      }
    }

    return this.mergeSections(sections).slice(0, 4);
  }

  private extractPaySection(root: HTMLElement): JobDescriptionSection | null {
    const payNode = root.querySelector('.content-pay-transparency, .pay-input, .pay-range');
    const payText = this.cleanText(payNode?.textContent || '');
    if (!payText) {
      return null;
    }

    return {
      title: 'Pay and benefits',
      items: [payText],
    };
  }

  private sectionsFromPlainText(text: string): JobDescriptionSection[] {
    const normalized = this.cleanText(text);
    if (!normalized) {
      return [];
    }

    const headingPatterns = [
      { marker: "WHAT YOU'LL DO", title: 'What you would do' },
      { marker: 'WHAT YOULL DO', title: 'What you would do' },
      { marker: 'REQUIRED QUALIFICATIONS', title: 'What they want' },
      { marker: 'MINIMUM QUALIFICATIONS', title: 'What they want' },
      { marker: 'QUALIFICATIONS', title: 'What they want' },
      { marker: 'US SALARY RANGE', title: 'Pay and benefits' },
      { marker: 'BENEFITS', title: 'Pay and benefits' },
    ];
    const found = headingPatterns
      .map((pattern) => ({ ...pattern, index: normalized.toUpperCase().indexOf(pattern.marker) }))
      .filter((pattern) => pattern.index >= 0)
      .sort((a, b) => a.index - b.index);

    if (!found.length) {
      return [{
        title: 'Role details',
        body: this.trimToSentences(normalized, 4),
        items: [],
      }];
    }

    return found.map((pattern, index) => {
      const start = pattern.index + pattern.marker.length;
      const end = found[index + 1]?.index ?? normalized.length;
      const sectionText = this.cleanText(normalized.slice(start, end));
      return {
        title: pattern.title,
        body: this.trimToSentences(sectionText, pattern.title === 'Pay and benefits' ? 2 : 3),
        items: [],
      };
    }).filter((section) => section.body);
  }

  private mapSectionTitle(rawTitle: string): string {
    const title = rawTitle.replace(/\s+/g, ' ').trim();
    const upper = title.toUpperCase();
    if (!title || title === '&nbsp;') {
      return '';
    }

    if (upper.includes("WHAT YOU'LL DO") || upper.includes('RESPONSIBILITIES') || upper.includes('THE ROLE')) {
      return 'What you would do';
    }

    if (upper.includes('REQUIRED') || upper.includes('QUALIFICATIONS') || upper.includes('WHAT YOU NEED')) {
      return 'What they want';
    }

    if (upper.includes('SALARY') || upper.includes('PAY') || upper.includes('BENEFITS') || upper.includes('COMPENSATION')) {
      return 'Pay and benefits';
    }

    if (upper.includes('SCAM') || upper.includes('PRIVACY') || upper.includes('HIRING')) {
      return 'Hiring notes';
    }

    return '';
  }

  private mergeSections(sections: JobDescriptionSection[]): JobDescriptionSection[] {
    const byTitle = new Map<string, JobDescriptionSection>();
    for (const section of sections) {
      const existing = byTitle.get(section.title);
      if (!existing) {
        byTitle.set(section.title, section);
        continue;
      }

      byTitle.set(section.title, {
        title: section.title,
        body: existing.body || section.body,
        items: this.unique([...existing.items, ...section.items]).slice(0, 8),
      });
    }

    return Array.from(byTitle.values());
  }

  private getDecodedDescriptionHtml(html: string | undefined): string {
    if (!html?.trim()) {
      return '';
    }

    return html.includes('&lt;') || html.includes('&gt;') ? this.decodeHtmlEntities(html) : html;
  }

  private decodeHtmlEntities(value: string): string {
    if (typeof document === 'undefined') {
      return value
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, '&')
        .replace(/&nbsp;/g, ' ');
    }

    let decoded = value;
    for (let i = 0; i < 2; i += 1) {
      const textarea = document.createElement('textarea');
      textarea.innerHTML = decoded;
      decoded = textarea.value;
    }
    return decoded;
  }

  private stripHtml(value: string): string {
    return value.replace(/<[^>]*>/g, ' ');
  }

  private cleanText(value: string): string {
    return this.decodeHtmlEntities(value)
      .replace(/\u00a0/g, ' ')
      .replace(/\s+/g, ' ')
      .replace(/\s+([.,;:!?])/g, '$1')
      .trim();
  }

  private trimToSentences(value: string, maxSentences: number): string {
    const text = this.cleanText(value);
    if (!text) {
      return '';
    }

    const parts = text.split(/([.!?])\s+(?=[A-Z])/);
    const sentences: string[] = [];
    for (let index = 0; index < parts.length; index += 2) {
      const sentence = `${parts[index] || ''}${parts[index + 1] || ''}`.trim();
      if (sentence) {
        sentences.push(sentence);
      }
    }

    return sentences.slice(0, maxSentences).join(' ').trim();
  }

  private firstMeaningfulText(...values: string[]): string {
    return values.map((value) => this.cleanText(value)).find((value) => value.length > 20) || '';
  }

  private compactLocation(location: string | undefined): string {
    return (location || '')
      .replace(/,\s*United States(?: of America)?$/i, '')
      .replace(/\s*\(HQ\)/i, '')
      .trim();
  }

  private unique(values: string[]): string[] {
    return Array.from(new Set(values.map((value) => this.cleanText(value)).filter(Boolean)));
  }

  private emptyDescriptionView(): JobDescriptionView {
    return {
      quickRead: '',
      sections: [],
      originalText: '',
      hasContent: false,
    };
  }

  private computeNewSinceLastVisit(jobs: CandidateJobSummary[]): void {
    const lastVisitKey = 'airral_last_jobs_visit';
    const lastVisit = localStorage.getItem(lastVisitKey);
    const lastVisitDate = lastVisit ? new Date(lastVisit) : null;

    if (lastVisitDate && !isNaN(lastVisitDate.getTime())) {
      this.newSinceLastVisit = jobs.filter(job => {
        const posted = job.sourceUpdatedAt ? new Date(job.sourceUpdatedAt) : null;
        return posted && posted > lastVisitDate;
      }).length;
    }

    localStorage.setItem(lastVisitKey, new Date().toISOString());
  }
}
