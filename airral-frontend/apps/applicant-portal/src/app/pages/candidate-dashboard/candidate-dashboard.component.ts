import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { catchError, forkJoin, map, Observable, of, timeout } from 'rxjs';
import { AuthService } from '@airral/shared-auth';
import { CandidatePortalService, FeedApiService } from '@airral/shared-api';
import { CandidateJobPageResponse, CandidateJobSummary, CandidateProfile, CompanyFeedPostModel, FeedPostType, NewsArticleModel, NewsCategoryModel, UpdateCandidateProfileRequest, User } from '@airral/shared-types';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { JobRoomsComponent } from './components/job-rooms/job-rooms.component';
import { CareerEventsComponent } from './components/career-events/career-events.component';
import { RecommendedJobsComponent } from './components/recommended-jobs/recommended-jobs.component';
import { MatchSetupComponent } from './components/match-setup/match-setup.component';
import { WorkspaceFeedComponent } from './components/workspace-feed/workspace-feed.component';
import {
  CAREER_EVENTS,
  JOB_ROOMS,
  RECOMMENDED_ROLES,
  WORKSPACE_POSTS
} from './data/candidate-dashboard.mock-data';
import {
  CareerEvent,
  CommunityPostDraft,
  DashboardView,
  JobRoom,
  RecommendedRole,
  WorkspacePost
} from './models/candidate-dashboard.models';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

interface RecommendedJobsPage {
  roles: RecommendedRole[];
  hasMore: boolean;
  nextOffset: number | null;
}

@Component({
  selector: 'app-candidate-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    JobRoomsComponent,
    CareerEventsComponent,
    RecommendedJobsComponent,
    MatchSetupComponent,
    WorkspaceFeedComponent,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule
  ],
  templateUrl: './candidate-dashboard.component.html',
  styleUrls: ['./candidate-dashboard.component.css', './candidate-dashboard.journey.css']
})
export class CandidateDashboardComponent implements OnInit {
  loading = true;
  error: string | null = null;
  journeyMessage = '';
  showMatchSetup = false;
  matchSetupSaving = false;
  resumeUploading = false;
  resumeUploadError = '';
  newsLoading = false;
  newsError = '';
  feedLoading = false;
  feedLoadingMore = false;
  feedHasMore = false;
  activeNewsCategory: NewsCategoryModel = 'TECH';

  activeView: DashboardView = 'jobs';

  profile: CandidateProfile | null = null;
  readonly jobRooms = JOB_ROOMS;
  readonly careerEvents = CAREER_EVENTS;
  newsArticles: NewsArticleModel[] = [];
  private communityPosts: WorkspacePost[] = [];
  private newsWorkspacePosts: WorkspacePost[] = [];
  allRecommendedRoles: RecommendedRole[] = [];
  recommendedRoles: RecommendedRole[] = [];
  searchTerm = '';
  jobsHasMore = false;
  jobsLoadingMore = false;
  private searchRefreshHandle: ReturnType<typeof setTimeout> | null = null;
  private searchRequestId = 0;
  private newsRequestId = 0;
  private newsHasLoaded = false;
  private feedHasLoaded = false;
  private feedPage = 1;
  private readonly jobsPageSize = 20;
  private readonly feedPageSize = 10;
  private jobsOffset = 0;
  private selectedPublicJobId: string | null = null;
  constructor(
    private route: ActivatedRoute,
    private authService: AuthService,
    private candidatePortalService: CandidatePortalService,
    private feedApiService: FeedApiService,
    private changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.selectedPublicJobId = this.route.snapshot.queryParamMap.get('jobId');
    const user = this.authService.getCurrentUser();
    if (user?.email) {
      this.loadCandidateData(user);
      return;
    }

    window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  }

  setView(view: DashboardView): void {
    this.activeView = view;
    this.journeyMessage = '';
    if (view === 'feed') {
      if (!this.feedHasLoaded && !this.feedLoading) {
        this.loadWorkspaceFeed();
      }
      if (!this.newsHasLoaded && !this.newsLoading) {
        this.refreshNewsFeed();
      }
    }
  }

  get workspacePosts(): WorkspacePost[] {
    return [...this.communityPosts, ...this.newsWorkspacePosts].sort((first, second) => {
      if (first.freshnessMinutes !== second.freshnessMinutes) {
        return first.freshnessMinutes - second.freshnessMinutes;
      }
      return second.depthScore - first.depthScore;
    });
  }

  get jobViewSignalPosts(): WorkspacePost[] {
    return this.newsWorkspacePosts.filter((post) => post.signalType).slice(0, 3);
  }

  handleSearchInput(event: Event): void {
    this.searchTerm = (event.target as HTMLInputElement).value;
    this.applyJobSearch();
    this.scheduleSearchRefresh();
  }

  loadCandidateData(user: User): void {
    this.loading = true;
    this.error = null;

    forkJoin({
      profile: this.candidatePortalService.getCandidateProfile(user.email),
      jobs: this.loadRecommendedRoles(),
    }).subscribe({
      next: ({ profile, jobs }) => {
        this.profile = profile;
        this.showMatchSetup = this.shouldShowMatchSetup(profile);
        this.applyRecommendedJobsPage(jobs);
        if (this.selectedPublicJobId) {
          this.journeyMessage = 'Your public job preview is attached. Finish match setup, then save or apply from the job workspace.';
        }
        this.loading = false;
        this.changeDetectorRef.detectChanges();
      },
      error: (err) => {
        if (String(err?.message || '').toLowerCase().includes('auth')) {
          window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
          return;
        }
        this.error = 'Failed to load your dashboard data';
        this.loading = false;
      }
    });
  }

  private loadRecommendedRoles(query?: string, offset = 0): Observable<RecommendedJobsPage> {
    return this.candidatePortalService.getRecommendedJobsPage(this.jobsPageSize, offset, undefined, query).pipe(
      timeout(30000),
      map((page) => this.mapCandidateJobPageToRoles(page)),
      catchError((error) => {
        console.error('AIRRAL jobs API error.', error);
        return of({
          roles: [],
          hasMore: false,
          nextOffset: null,
        });
      })
    );
  }

  private loadNewsArticles(): Observable<NewsArticleModel[] | null> {
    this.newsError = '';
    return this.feedApiService.getNewsFeed({ category: this.activeNewsCategory, size: 30 }).pipe(
      timeout(8000),
      map((page) => page.items || []),
      catchError((error) => {
        console.error('AIRRAL news feed API error.', error);
        this.newsError = 'News sources are not available right now. Try refresh again in a moment.';
        return of(null);
      })
    );
  }

  refreshNewsFeed(): void {
    const requestId = ++this.newsRequestId;
    this.newsLoading = true;
    this.newsError = '';
    this.loadNewsArticles().subscribe((articles) => {
      if (requestId !== this.newsRequestId) {
        return;
      }

      if (articles !== null) {
        this.applyNewsArticles(articles);
      } else if (!this.newsArticles.length) {
        this.applyNewsArticles([]);
      }
      this.newsLoading = false;
      this.newsHasLoaded = true;
      if (articles !== null && articles.length === 0 && !this.newsError) {
        this.newsError = 'No current articles came back from the selected sources yet.';
      }
      this.changeDetectorRef.detectChanges();
    });
  }

  private applyNewsArticles(articles: NewsArticleModel[]): void {
    this.newsArticles = articles;
    this.newsWorkspacePosts = articles.map((article) => this.mapNewsArticleToWorkspacePost(article));
  }

  loadWorkspaceFeed(page = 1): void {
    if (page > 1 && (this.feedLoadingMore || !this.feedHasMore)) {
      return;
    }

    this.feedLoading = page === 1;
    this.feedLoadingMore = page > 1;

    this.feedApiService.getPublicFeed({ page, pageSize: this.feedPageSize }).pipe(
      timeout(8000),
      map((response) => ({
        posts: (response.items || []).map((post) => this.mapFeedPostToWorkspacePost(post)),
        hasMore: Boolean(response.hasNext),
      })),
      catchError((error) => {
        console.warn('AIRRAL community feed API unavailable.', error);
        return of({ posts: [] as WorkspacePost[], hasMore: false });
      })
    ).subscribe(({ posts, hasMore }) => {
      if (page === 1) {
        this.communityPosts = posts;
      } else {
        const existingKeys = new Set(this.communityPosts.map((post) => this.getWorkspacePostKey(post)));
        this.communityPosts = [
          ...this.communityPosts,
          ...posts.filter((post) => !existingKeys.has(this.getWorkspacePostKey(post))),
        ];
      }

      this.feedPage = page;
      this.feedHasMore = hasMore;
      this.feedHasLoaded = true;
      this.feedLoading = false;
      this.feedLoadingMore = false;
      this.changeDetectorRef.detectChanges();
    });
  }

  loadMoreWorkspaceFeed(): void {
    this.loadWorkspaceFeed(this.feedPage + 1);
  }

  private applyRecommendedJobsPage(page: RecommendedJobsPage): void {
    this.allRecommendedRoles = [...page.roles];
    this.jobsHasMore = page.hasMore;
    this.jobsOffset = page.nextOffset ?? this.allRecommendedRoles.length;
    this.applyJobSearch();
  }

  private applyJobSearch(): void {
    const query = this.searchTerm.trim().toLowerCase();
    if (!query) {
      this.recommendedRoles = [...this.allRecommendedRoles];
      return;
    }

    this.recommendedRoles = this.allRecommendedRoles.filter((role) =>
      [
        role.title,
        role.company,
        role.location,
        role.workMode,
        role.salary,
        role.sourceName,
        ...role.tags,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      .includes(query)
    );
  }

  private scheduleSearchRefresh(): void {
    if (this.searchRefreshHandle) {
      clearTimeout(this.searchRefreshHandle);
    }

    this.searchRefreshHandle = setTimeout(() => {
      this.refreshRecommendedRolesFromServer(this.searchTerm.trim());
    }, 350);
  }

  private refreshRecommendedRolesFromServer(query: string): void {
    const requestId = ++this.searchRequestId;
    this.loadRecommendedRoles(query || undefined, 0).subscribe((roles) => {
      if (requestId !== this.searchRequestId) {
        return;
      }

      this.applyRecommendedJobsPage(roles);
      this.changeDetectorRef.detectChanges();
    });
  }

  loadMoreRecommendedRoles(): void {
    if (this.jobsLoadingMore || !this.jobsHasMore) {
      return;
    }

    const query = this.searchTerm.trim() || undefined;
    this.jobsLoadingMore = true;
    this.loadRecommendedRoles(query, this.jobsOffset).subscribe((page) => {
      const existingKeys = new Set(this.allRecommendedRoles.map((role) => this.getRoleKey(role)));
      const newRoles = page.roles.filter((role) => !existingKeys.has(this.getRoleKey(role)));

      this.allRecommendedRoles = [...this.allRecommendedRoles, ...newRoles];
      this.jobsHasMore = page.hasMore;
      this.jobsOffset = page.nextOffset ?? this.allRecommendedRoles.length;
      this.jobsLoadingMore = false;
      this.applyJobSearch();
      this.changeDetectorRef.detectChanges();
    });
  }

  handleRoleApply(role: RecommendedRole): void {
    if (role.applyUrl && role.applyMode === 'EXTERNAL_APPLY') {
      window.open(role.applyUrl, '_blank', 'noopener');
      this.journeyMessage = `Opened the employer apply page for ${role.title} at ${role.company}. AIRRAL will keep the room, resume check, and next step attached here.`;
      return;
    }

    this.journeyMessage = `${role.title} at ${role.company} is ready. Apply first, then AIRRAL will keep the room, resume check, and next step attached to this role.`;
  }

  handleRoleRoom(role: RecommendedRole): void {
    this.activeView = 'rooms';
    this.journeyMessage = role.connections > 0
      ? `${role.connections} people can help with ${role.company}. Ask the room about salary, interview loop, and recruiter timing before you apply.`
      : `Start a focused room for ${role.company}. Bring the job link, salary questions, and interview prep into one place.`;
  }

  handleRoomJoin(room: JobRoom): void {
    this.journeyMessage = `Joined ${room.name}. Your next best move is to share one role or question there.`;
  }

  handleCreateRoom(): void {
    this.setView('rooms');
    this.journeyMessage = 'Create a focused room around one job, company, event, or founder group. Invite people with a link or QR code.';
  }

  handleAskStarted(topic: string): void {
    this.activeView = 'rooms';
    this.journeyMessage = `Start a ${topic} with one clear role attached. AIRRAL will route it to the room most likely to help.`;
  }

  handleEventReserve(event: CareerEvent): void {
    this.journeyMessage = `Reserved: ${event.title}. AIRRAL will keep this tied to your weekly search momentum.`;
  }

  handlePostAction(post: WorkspacePost): void {
    if (post.action === 'Read') {
      this.openPostSource(post);
      return;
    }

    if (post.action === 'View jobs') {
      this.activeView = 'jobs';
      this.searchTerm = post.room === 'Company signal' ? '' : post.room;
      this.applyJobSearch();
      this.refreshRecommendedRolesFromServer(this.searchTerm.trim());
      this.journeyMessage = `Showing roles connected to ${post.room}. Use the news signal as context before you apply.`;
      return;
    }

    if (post.action === 'Check context') {
      this.activeView = 'feed';
      this.journeyMessage = `Saved ${post.room} as a company to research before applying. Check the source and compare it with current openings.`;
      return;
    }

    this.activeView = 'rooms';
    this.journeyMessage = `${post.action}: ${post.room} is the right place to continue this thread.`;
  }

  openPostSource(post: WorkspacePost, event?: Event): void {
    event?.stopPropagation();
    if (post.sourceUrl) {
      window.open(post.sourceUrl, '_blank', 'noopener');
    }
  }

  getSignalTypeLabel(post: WorkspacePost): string {
    return (post.signalType || post.postType || 'Company signal')
      .toString()
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (match) => match.toUpperCase());
  }

  getSignalSourceLabel(post: WorkspacePost): string {
    return post.sourceName || post.author || 'AIRRAL Radar';
  }

  handleCommunityPostCreated(draft: CommunityPostDraft): void {
    const post = this.toWorkspacePost(draft);
    this.communityPosts = [post, ...this.communityPosts];
    this.journeyMessage = 'Posted to the career feed. People can now reply, save it, or continue the conversation in a room.';

    this.feedApiService.createCommunityPost({
      postType: draft.postType,
      visibility: draft.visibility,
      topic: draft.topic,
      content: draft.content,
      targetType: draft.targetLabel ? 'COMPANY' : 'GENERAL',
      targetLabel: draft.targetLabel,
    }).pipe(
      timeout(5000),
      catchError((error) => {
        console.warn('AIRRAL feed API unavailable; optimistic post is local for this session.', error);
        this.journeyMessage = 'Your post is visible locally, but AIRRAL could not save it yet. Try again if it does not appear after refresh.';
        return of(null);
      })
    ).subscribe((savedPost) => {
      if (savedPost) {
        this.communityPosts = [
          this.mapFeedPostToWorkspacePost(savedPost),
          ...this.communityPosts.filter((item) => this.getWorkspacePostKey(item) !== this.getWorkspacePostKey(post)),
        ];
      }
      this.changeDetectorRef.detectChanges();
    });
  }

  handleMatchSetupSave(request: UpdateCandidateProfileRequest): void {
    this.matchSetupSaving = true;
    this.error = null;

    this.candidatePortalService.updateCandidateProfile(request).pipe(
      timeout(6000),
      catchError((error) => {
        console.warn('AIRRAL profile API unavailable; profile setup was not saved.', error);
        this.error = 'We could not save your profile setup. Please try again.';
        return of(null);
      })
    ).subscribe((profile) => {
      if (!profile) {
        this.matchSetupSaving = false;
        this.changeDetectorRef.detectChanges();
        return;
      }

      this.profile = profile;
      this.showMatchSetup = false;
      this.matchSetupSaving = false;
      localStorage.removeItem('airral_match_setup_skipped');
      this.journeyMessage = 'Your match setup is saved. Jobs are now ordered around your role, location, salary, and skills.';
      this.refreshRecommendedRolesFromServer(request.headline || '');
      this.changeDetectorRef.detectChanges();
    });
  }

  handleResumeUpload(file: File): void {
    if (this.resumeUploading) {
      return;
    }

    this.resumeUploading = true;
    this.resumeUploadError = '';
    this.error = null;

    this.candidatePortalService.uploadCandidateResume(file).pipe(
      timeout(20000),
      catchError((error) => {
        console.warn('AIRRAL resume upload failed.', error);
        this.resumeUploadError = 'Resume upload failed. Please use a PDF or DOCX under 5MB.';
        return of(null);
      })
    ).subscribe((profile) => {
      if (profile) {
        this.profile = profile;
        this.journeyMessage = 'Resume uploaded and saved to your AIRRAL profile.';
      }

      this.resumeUploading = false;
      this.changeDetectorRef.detectChanges();
    });
  }

  handleResumeFileInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) {
      this.handleResumeUpload(file);
    }
    input.value = '';
  }

  handleMatchSetupSkip(): void {
    this.showMatchSetup = false;
    localStorage.setItem('airral_match_setup_skipped', new Date().toISOString());
    this.journeyMessage = 'You can set up matching later. For now, AIRRAL is showing the newest US roles first.';
  }

  reopenMatchSetup(): void {
    this.showMatchSetup = true;
    this.journeyMessage = '';
  }

  handleResumeCheck(): void {
    this.setView('resume');
    this.journeyMessage = 'Run the ATS check against one target role so the feedback is specific, not generic.';
  }

  handleFounderGroup(): void {
    this.setView('founders');
    this.journeyMessage = 'Founder groups can use a QR code to bring candidates, teammates, or event attendees into one private room.';
  }

  logout(): void {
    this.authService.logout();
    window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  }

  getLocation(): string {
    return this.profile?.location || 'Location flexible';
  }

  getWorkMode(): string {
    return this.profile?.preferredWorkMode || 'Any work mode';
  }

  getSalaryLabel(): string {
    if (this.profile?.salaryExpectationMin && this.profile.salaryExpectationMax) {
      const formatter = new Intl.NumberFormat('en-US', {
        notation: 'compact',
        maximumFractionDigits: 0,
        style: 'currency',
        currency: this.profile.salaryCurrency || 'USD',
      });
      return `${formatter.format(this.profile.salaryExpectationMin)}-${formatter.format(this.profile.salaryExpectationMax)}`;
    }

    return 'Salary flexible';
  }

  getProfileCompletion(): number {
    return this.profile?.profileCompletion ?? 0;
  }

  getPrimaryRole(): RecommendedRole {
    return this.recommendedRoles[0] ?? RECOMMENDED_ROLES[0];
  }

  getPrimaryRoom(): JobRoom {
    return this.jobRooms[0];
  }

  private shouldShowMatchSetup(profile: CandidateProfile | null): boolean {
    if (!profile || localStorage.getItem('airral_match_setup_skipped')) {
      return false;
    }

    const hasTargetRole = Boolean(profile.headline?.trim());
    const hasLocation = Boolean(profile.location?.trim());
    const hasWorkMode = Boolean(profile.preferredWorkMode);
    const hasSalary = Boolean(profile.salaryExpectationMin && profile.salaryExpectationMax);
    const hasSkills = Boolean(profile.skills?.length);
    const hasMatchPreferences = Boolean(profile.matchPreferences?.targetRoles?.length || profile.matchPreferences?.mustHaveSkills?.length);

    return [hasTargetRole, hasLocation, hasWorkMode, hasSalary, hasSkills, hasMatchPreferences].filter(Boolean).length < 5;
  }

  private mapCandidateJobPageToRoles(page: CandidateJobPageResponse): RecommendedJobsPage {
    return {
      roles: (page.jobs || []).map((job) => this.mapCandidateJobToRole(job)),
      hasMore: Boolean(page.hasMore),
      nextOffset: page.nextOffset ?? null,
    };
  }

  private mapCandidateJobToRole(job: CandidateJobSummary): RecommendedRole {
    const workMode = this.formatWorkMode(job.workMode);
    const company = job.companyName || 'Company';

    return {
      sourceJobId: job.jobId,
      sourceType: job.sourceType,
      sourceName: job.sourceName,
      sourceBoardToken: job.sourceBoardToken,
      externalJobId: job.externalJobId,
      applyUrl: job.applyUrl,
      jobUrl: job.jobUrl,
      applyMode: job.applyMode,
      title: job.title,
      company,
      companyDomain: job.companyDomain,
      companyLogoUrl: job.companyLogoUrl,
      location: job.location || 'Location not listed',
      workMode,
      match: job.matchScore ?? 78,
      salary: job.salaryLabel || 'Salary not listed',
      posted: job.postedLabel || 'Recently updated',
      applicants: 0,
      reviewScore: 0,
      reviewCount: 0,
      connections: job.connectionsCount ?? 0,
      easyApply: Boolean(job.easyApplyAvailable),
      jobQualityScore: job.jobQualityScore,
      qualityReasons: job.qualityReasons,
      totalCompLabel: job.totalCompLabel,
      compensationConfidence: job.compensationConfidence,
      companyInsight: `Official ${job.sourceName || 'employer'} posting from ${company}. Open the role to review the full job description and apply on the employer site.`,
      interviewSignal: job.department ? `${job.department} role from ${job.sourceName || 'employer source'}` : `Official ${job.sourceName || 'employer'} posting`,
      tags: job.tags?.length ? job.tags : [workMode],
    };
  }

  private getRoleKey(role: RecommendedRole): string {
    return role.sourceJobId || role.externalJobId || `${role.company}-${role.title}-${role.location}`;
  }

  private getWorkspacePostKey(post: WorkspacePost): string {
    return `${post.sourceUrl || post.author}-${post.title}-${post.freshnessMinutes}`;
  }

  private toWorkspacePost(draft: CommunityPostDraft): WorkspacePost {
    return {
      postType: draft.postType,
      author: this.profile?.firstName ? `${this.profile.firstName} ${this.profile.lastName || ''}`.trim() : 'AIRRAL member',
      role: this.profile?.headline || 'Applicant',
      room: draft.targetLabel || 'Career Feed',
      lens: 'for_you',
      icon: this.iconForPostType(draft.postType),
      title: this.titleForPostType(draft.postType, draft.targetLabel),
      body: draft.content,
      whyRecommended: this.reasonForPostType(draft.postType),
      depthScore: 76,
      freshnessMinutes: 0,
      tags: this.tagsForPostType(draft.postType, draft.targetLabel),
      replies: 0,
      saves: 0,
      action: this.actionForPostType(draft.postType),
    };
  }

  private mapFeedPostToWorkspacePost(post: CompanyFeedPostModel): WorkspacePost {
    const postType = post.postType || 'JOB_SEARCH_ASK';
    const target = post.targetLabel || post.companyName || post.topic;
    const replies = post.commentCount ?? post.engagement?.responseCount ?? 0;
    const saves = post.practicalCount ?? post.engagement?.followerActions ?? 0;
    const useful = post.usefulCount ?? post.engagement?.usefulCount ?? 0;
    const inspiring = post.inspiringCount ?? post.engagement?.inspiringCount ?? 0;

    return {
      postType,
      author: post.authorDisplayName || post.companyName || 'AIRRAL member',
      role: post.authorType === 'COMPANY' ? 'Company update' : post.topic || 'Applicant',
      room: target || 'Career Feed',
      lens: 'for_you',
      icon: this.iconForPostType(postType),
      title: post.topic || this.titleForPostType(postType, target),
      body: post.content,
      whyRecommended: this.reasonForPostType(postType),
      depthScore: Math.min(98, Math.max(65, 74 + useful + inspiring * 2 + replies * 3)),
      freshnessMinutes: this.minutesSince(post.publishedAt || post.createdAt),
      tags: this.tagsForPostType(postType, target),
      replies,
      saves,
      action: this.actionForPostType(postType),
    };
  }

  private mapNewsArticleToWorkspacePost(article: NewsArticleModel): WorkspacePost {
    const signalType = (article.signalType || 'COMPANY_SIGNAL').toUpperCase();
    const source = article.sourceName || article.sourceDomain || 'News source';

    return {
      postType: this.postTypeForSignal(signalType),
      signalType,
      author: source,
      role: `${this.labelForSignal(signalType)} signal`,
      room: source,
      lens: 'for_you',
      icon: this.iconForSignal(signalType),
      title: article.title,
      body: article.summary || article.title,
      whyRecommended: article.whyItMatters || 'Company news gives context before you apply.',
      depthScore: article.relevanceScore ?? this.scoreForSignal(signalType),
      freshnessMinutes: this.minutesSince(article.publishedAt),
      tags: article.tags?.length ? article.tags : [this.labelForSignal(signalType), source],
      replies: 0,
      saves: 0,
      action: article.primaryAction || 'Read',
      sourceName: article.sourceName,
      sourceUrl: article.sourceUrl,
      imageUrl: article.imageUrl,
      confidence: article.sourceTrustTier || 'MEDIUM',
      linkedJobsCount: 0,
    };
  }

  private minutesSince(timestamp?: string): number {
    if (!timestamp) {
      return 1;
    }

    const value = new Date(timestamp).getTime();
    if (Number.isNaN(value)) {
      return 1;
    }

    return Math.max(1, Math.floor((Date.now() - value) / 60000));
  }

  private iconForPostType(postType: FeedPostType): string {
    switch (postType) {
      case 'COMPANY_SIGNAL':
        return 'domain';
      case 'HIRING_PULSE':
        return 'work';
      case 'ROLE_SPOTLIGHT':
        return 'badge';
      case 'CAREER_UPDATE':
        return 'trending_up';
      case 'INTERVIEW_NOTE':
        return 'assignment';
      case 'SALARY_INTEL':
        return 'payments';
      case 'REFERRAL_OFFER':
        return 'handshake';
      default:
        return 'help';
    }
  }

  private titleForPostType(postType: FeedPostType, targetLabel?: string): string {
    const target = targetLabel ? ` about ${targetLabel}` : '';
    switch (postType) {
      case 'CAREER_UPDATE':
        return `Career update${target}`;
      case 'INTERVIEW_NOTE':
        return `Interview note${target}`;
      case 'SALARY_INTEL':
        return `Salary intel${target}`;
      case 'REFERRAL_OFFER':
        return `Referral offer${target}`;
      default:
        return `Can anyone help${target}?`;
    }
  }

  private reasonForPostType(postType: FeedPostType): string {
    switch (postType) {
      case 'CAREER_UPDATE':
        return 'Career updates help followers know when to refer, message, or share roles.';
      case 'INTERVIEW_NOTE':
        return 'Interview notes make the next applicant more prepared before they apply.';
      case 'SALARY_INTEL':
        return 'Salary context reduces uncertainty before someone spends time applying.';
      case 'REFERRAL_OFFER':
        return 'Referral posts turn the feed into warm access, not just scrolling.';
      default:
        return 'Focused asks get routed to people and rooms that can help.';
    }
  }

  private tagsForPostType(postType: FeedPostType, targetLabel?: string): string[] {
    const tags = [targetLabel, postType.replace(/_/g, ' ').toLowerCase()]
      .filter(Boolean)
      .map((tag) => String(tag));
    return tags.length ? tags : ['Job search'];
  }

  private actionForPostType(postType: FeedPostType): string {
    return postType === 'REFERRAL_OFFER' ? 'Message' : 'Reply';
  }

  private postTypeForSignal(signalType: string): FeedPostType {
    if (signalType === 'HIRING') {
      return 'HIRING_PULSE';
    }
    return 'COMPANY_SIGNAL';
  }

  private iconForSignal(signalType: string): string {
    switch (signalType) {
      case 'FUNDING':
        return 'trending_up';
      case 'HIRING':
        return 'work';
      case 'PRODUCT_LAUNCH':
        return 'rocket_launch';
      case 'ACQUISITION':
        return 'account_tree';
      case 'PARTNERSHIP':
        return 'handshake';
      case 'RISK':
        return 'report';
      default:
        return 'domain';
    }
  }

  private labelForSignal(signalType: string): string {
    return signalType.replace(/_/g, ' ').toLowerCase();
  }

  private scoreForSignal(signalType: string, confidence?: string): number {
    const base = confidence === 'HIGH' ? 86 : 76;
    return signalType === 'FUNDING' || signalType === 'HIRING' ? base + 6 : base;
  }

  private actionForSignal(signalType: string): string {
    if (signalType === 'FUNDING' || signalType === 'HIRING' || signalType === 'PRODUCT_LAUNCH') {
      return 'View jobs';
    }
    if (signalType === 'RISK') {
      return 'Check context';
    }
    return 'Ask room';
  }

  private formatWorkMode(workMode?: string): string {
    switch ((workMode || '').toUpperCase()) {
      case 'REMOTE':
        return 'Remote';
      case 'HYBRID':
        return 'Hybrid';
      case 'ONSITE':
        return 'On-site';
      default:
        return 'Work mode not listed';
    }
  }

}
