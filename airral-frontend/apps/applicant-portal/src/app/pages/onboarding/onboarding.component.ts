import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { AuthService, isEmailNotVerifiedError } from '@airral/shared-auth';
import { UpdateCandidateProfileRequest, ResumeHealthScore } from '@airral/shared-types';
import { timeout } from 'rxjs';
import { markUserOnboarded } from '../../guards/onboarding.guard';
import { saveOnboardingJobSearchSeed } from '../../utils/job-search-seed';

type OnboardingStep = 1 | 2 | 3;

interface RoleOption {
  label: string;
  /** Live postings behind this option, straight from the catalogue. */
  jobCount: number;
  selected: boolean;
}

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.css',
})
export class OnboardingComponent implements OnInit {

  /**
   * Leave onboarding without answering it.
   *
   * <p>Marks onboarding complete so the guard stops redirecting here on every
   * navigation. That is the honest interpretation of the flag: it records that
   * the user has been past this screen, not that they filled it in. The
   * questions are all on the profile page, and matches simply stay generic
   * until they are answered -- which is a worse product, not a broken one.
   */
  skipOnboarding(): void {
    markUserOnboarded(this.auth.getCurrentUser()?.email);
    this.router.navigateByUrl('/jobs');
  }
  step: OnboardingStep = 1;
  saving = false;
  setupError = '';

  // ── Step 1 — Roles ───────────────────────────────
  //
  // These used to be 24 hardcoded options, every one of them tech or
  // white-collar. Measured over the live catalogue, about 70% of postings are
  // neither and the four biggest role families are retail, warehouse, software
  // and sales -- there was no option at all for warehouse, fulfillment,
  // cashier, driver, cook or housekeeping work. So the list is no longer
  // written here. It is counted out of the postings we actually hold, which is
  // the only version of this that cannot drift back out of step with them.
  roleOptions: RoleOption[] = [];
  roleFamiliesLoading = true;
  roleFamiliesError = '';

  /**
   * Live postings that fit none of the families, and the catalogue total.
   *
   * <p>Shown on the page rather than hidden. The grouping resolves about 87% of
   * titles, and pretending the remaining eighth does not exist would tell a
   * candidate whose work is in that tail that we have nothing for them. Saying
   * the number, and pointing at the free-text box, is the truthful version.
   */
  unclassifiedJobCount = 0;
  totalJobCount = 0;

  showAllRoles = false;
  customRole = '';
  seniority = '';

  /**
   * Why step 1 could not be passed, shown only after a Continue that did nothing.
   *
   * <p>Second best, and it exists only as a backstop. The requirement is on the
   * page from the moment it loads ({@link step1Requirement}), because the bug
   * being fixed here was a Continue button sitting disabled with no explanation
   * anywhere on the screen -- a user picked a role, clicked, and was told
   * nothing at all.
   */
  step1Error = '';

  /** How many role options are shown before the "show all" control. */
  private static readonly ROLE_PREVIEW_COUNT = 12;

  // Step 2 — Resume
  resumeFile: File | null = null;
  resumeUploading = false;
  resumeUploaded = false;
  resumeError = '';
  resumeHealth: ResumeHealthScore | null = null;
  resumeHealthLoading = false;

  // Step 3 — Preferences
  workMode = '';
  location = '';
  salaryMin: number | null = null;
  salaryMax: number | null = null;
  needsSponsorship = false;

  // Popular US metro areas for quick selection
  popularCities = [
    'New York, NY',
    'San Francisco, CA',
    'Los Angeles, CA',
    'Chicago, IL',
    'Seattle, WA',
    'Austin, TX',
    'Dallas, TX',
    'Boston, MA',
    'Denver, CO',
    'Atlanta, GA',
    'Miami, FL',
    'Washington, DC',
    'Houston, TX',
    'Raleigh, NC',
    'Phoenix, AZ',
    'Nashville, TN',
    'San Diego, CA',
    'Minneapolis, MN',
    'Remote',
  ];
  showAllCities = false;

  constructor(
    private readonly router: Router,
    private readonly candidateApi: CandidatePortalService,
    private readonly auth: AuthService,
    // This bundle runs without zone.js, so a field written from an HTTP
    // callback does not repaint on its own. Every async write below marks the
    // component, the way the profile page already does. Without it the upload
    // on step 2 appeared to hang forever and a failed save on step 3 showed
    // nothing -- the same "blocked and told nothing" failure as step 1.
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadRoleFamilies();
  }

  loadRoleFamilies(): void {
    this.roleFamiliesLoading = true;
    this.roleFamiliesError = '';

    // A request that never settles calls neither handler, so the spinner would
    // sit there for as long as the browser held the socket -- the same "told
    // nothing" state as the disabled button, just slower. The catalogue query is
    // a grouped scan over the whole live corpus on a shared-core database, so
    // 15s is deliberately generous; past that, the error arm is the better
    // screen because it offers Try again.
    this.candidateApi.getJobRoleFamilies().pipe(timeout(15000)).subscribe({
      next: (catalog) => {
        // Keep anything already ticked, so a retry does not silently discard
        // choices the user had made before the first load failed.
        const alreadyChosen = new Set(this.roleOptions.filter((r) => r.selected).map((r) => r.label));
        this.roleOptions = (catalog?.families ?? []).map((family) => ({
          label: family.label,
          jobCount: family.jobCount,
          selected: alreadyChosen.has(family.label),
        }));
        this.unclassifiedJobCount = catalog?.unclassifiedJobCount ?? 0;
        this.totalJobCount = catalog?.totalJobCount ?? 0;
        this.roleFamiliesLoading = false;
        this.changeDetectorRef.markForCheck();
      },
      error: () => {
        // Deliberately no fallback list. Shipping a hardcoded list when the
        // real one is unavailable is exactly the bug being fixed: it would be
        // a set of roles we cannot say we have jobs for. The free-text box is
        // always on the page, so this is a worse step, not a blocked one.
        this.roleFamiliesError = 'We could not load the role list just now.';
        this.roleFamiliesLoading = false;
        this.changeDetectorRef.markForCheck();
      },
    });
  }

  // ── Step navigation ──────────────────────────────

  /**
   * One role. Not a level.
   *
   * <p>Step 1 asks what work someone wants, and a role answers that; a level
   * does not. Requiring both is what made this screen impassable in silence --
   * with a role picked and no level, Continue sat disabled and the page said
   * nothing -- and the level was never load-bearing enough to justify it.
   * Measured over live postings, 17% carry no seniority at all, and the ladder
   * offered here (Entry/Mid/Senior/Staff/Lead) does not describe most of the
   * catalogue: a cashier or a warehouse worker has no "Staff" level. The
   * product also already reads experience from an uploaded resume and from the
   * posting text, and says UNKNOWN when it cannot.
   *
   * <p>The level stays on the page but it is not load-bearing anywhere yet:
   * finish() stores it under matchPreferences.seniority and no reader exists
   * for that key -- CandidateJobSearchService.toCandidateMatchContext does not
   * lift it into the match context, and its only seniority input is
   * yearsOfExperience derived from the resume. That is why the note beside the
   * control does not promise it affects ranking.
   */
  get canProceedStep1(): boolean {
    return this.selectedRoles.length > 0;
  }

  get canProceedStep2(): boolean {
    return true; // resume is optional
  }

  /**
   * What step 1 needs, stated before the user tries to continue.
   *
   * <p>This sits next to the role grid at all times. The whole failure being
   * fixed was a requirement that existed only inside a disabled attribute.
   */
  get step1Requirement(): string {
    const chosen = this.selectedRoles.length;
    if (chosen === 0) {
      // Has to be true of the screen it is sitting on. While the catalogue is
      // loading, or after it failed, there is no grid to pick from and telling
      // someone to pick a role points at nothing -- a smaller version of the
      // bug being fixed.
      if (this.roleFamiliesLoading) {
        return 'Loading the roles we have jobs for. Pick one when they appear, or type your own below -- one role is all this step needs.';
      }
      if (this.roleOptions.length === 0) {
        return 'Type the role you want in the box below to continue. One role is all this step needs.';
      }
      return 'Pick at least one role to continue, or type your own below. Nothing else on this step is required.';
    }
    if (chosen === 1) {
      return '1 role chosen. Continue when you are ready, or pick more.';
    }
    return `${chosen} roles chosen. Continue when you are ready, or pick more.`;
  }

  get criticalIssueCount(): number {
    return this.resumeHealth?.issues.filter(i => i.severity === 'critical').length ?? 0;
  }

  get warningIssueCount(): number {
    return this.resumeHealth?.issues.filter(i => i.severity === 'warning').length ?? 0;
  }

  get selectedRoles(): string[] {
    const selected = this.roleOptions.filter((r) => r.selected).map((r) => r.label);
    if (this.customRole.trim()) {
      selected.push(this.customRole.trim());
    }
    return selected;
  }

  get visibleRoleOptions(): RoleOption[] {
    return this.showAllRoles
      ? this.roleOptions
      : this.roleOptions.slice(0, OnboardingComponent.ROLE_PREVIEW_COUNT);
  }

  get hiddenRoleCount(): number {
    return Math.max(0, this.roleOptions.length - OnboardingComponent.ROLE_PREVIEW_COUNT);
  }

  nextStep(): void {
    if (this.step === 1) {
      if (!this.canProceedStep1) {
        // Reachable because the button is not disabled. A disabled control that
        // explains nothing is the bug; a live control that says what is missing
        // is the fix. It has to name a control that is actually on screen,
        // which the role grid is not while the catalogue is loading or failed.
        this.step1Error = this.roleOptions.length > 0
          ? 'Pick a role above, or type one in the box, so we know what to look for.'
          : 'Type the role you want in the box above, so we know what to look for.';
        return;
      }
      this.step1Error = '';
      this.step = 2;
      return;
    }

    if (this.step === 2) {
      this.step = 3;
    }
  }

  prevStep(): void {
    if (this.step > 1) {
      this.step = (this.step - 1) as OnboardingStep;
    }
  }

  toggleRole(role: RoleOption): void {
    role.selected = !role.selected;
    this.clearStep1ErrorOnceAnswered();
  }

  onCustomRoleChanged(): void {
    this.clearStep1ErrorOnceAnswered();
  }

  /** Stop nagging the moment the requirement is met. */
  private clearStep1ErrorOnceAnswered(): void {
    if (this.canProceedStep1) {
      this.step1Error = '';
    }
  }

  selectCity(city: string): void {
    if (this.location === city) {
      this.location = '';
    } else {
      this.location = city;
    }
  }

  get visibleCities(): string[] {
    return this.showAllCities ? this.popularCities : this.popularCities.slice(0, 8);
  }

  /**
   * Whether the typed salary range is the wrong way round.
   *
   * <p>Nothing on step 3 blocks, and this does not either -- it says what we
   * are going to do with the numbers instead of rejecting them, because a
   * silent correction is the same class of problem as a silent block.
   */
  get salaryRangeInverted(): boolean {
    return this.salaryMin != null && this.salaryMax != null && this.salaryMin > this.salaryMax;
  }

  // ── Resume upload ────────────────────────────────
  onResumeSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    this.resumeFile = file;
    this.resumeError = '';
    this.resumeUploading = true;
    this.resumeHealth = null;

    this.candidateApi.uploadCandidateResume(file).subscribe({
      next: () => {
        this.resumeUploaded = true;
        this.resumeUploading = false;
        this.changeDetectorRef.markForCheck();
        this.fetchResumeHealth();
      },
      error: (error) => {
        // New accounts reach this step before they have had a chance to open
        // the verification email, so this is the likeliest refusal here.
        this.resumeError = isEmailNotVerifiedError(error)
          ? 'Verify your email first: open the link we just sent you, then add your resume. You can skip this step for now.'
          : 'Upload failed. Try a PDF or DOCX under 5 MB.';
        this.resumeUploading = false;
        this.changeDetectorRef.markForCheck();
      },
    });
  }

  private fetchResumeHealth(): void {
    this.resumeHealthLoading = true;
    this.candidateApi.getResumeHealth().subscribe({
      next: (health) => {
        this.resumeHealth = health;
        this.resumeHealthLoading = false;
        this.changeDetectorRef.markForCheck();
      },
      error: () => {
        this.resumeHealthLoading = false;
        this.changeDetectorRef.markForCheck();
      },
    });
  }

  // ── Final submit ─────────────────────────────────
  finish(): void {
    if (this.saving) return;
    this.saving = true;
    this.setupError = '';

    // An inverted range is stored the way round it can be used, which is what
    // the page said would happen while the user was typing it.
    const lowerSalary = this.salaryRangeInverted ? this.salaryMax : this.salaryMin;
    const upperSalary = this.salaryRangeInverted ? this.salaryMin : this.salaryMax;

    const request: UpdateCandidateProfileRequest = {
      matchPreferences: {
        targetRoles: this.selectedRoles,
        seniority: this.seniority || undefined,
        needsSponsorship: this.needsSponsorship || undefined,
        searchStatus: 'ACTIVE',
      },
      preferredWorkMode: (this.workMode as any) || undefined,
      location: this.location.trim() || undefined,
      salaryExpectationMin: lowerSalary ?? undefined,
      salaryExpectationMax: upperSalary ?? undefined,
    };

    // "Find my jobs" disables itself while saving and is the only way off this
    // step, so a request that hangs rather than fails leaves "Setting up..." on
    // screen with no error and no way forward -- the same dead end as step 1,
    // reached differently. A timeout turns the hang into the message below.
    // Re-sending is safe: this is a PUT of the same body. 12s matches the
    // profile page, which already guards its load this way.
    this.candidateApi.updateCandidateProfile(request).pipe(timeout(12000)).subscribe({
      next: () => {
        const email = this.auth.getCurrentUser()?.email;
        markUserOnboarded(email);
        saveOnboardingJobSearchSeed(email, request);
        this.router.navigate(['/jobs'], { queryParams: { from: 'onboarding' } });
      },
      error: () => {
        this.setupError = 'We could not save your preferences yet. Please try again.';
        this.saving = false;
        this.changeDetectorRef.markForCheck();
      },
    });
  }
}
