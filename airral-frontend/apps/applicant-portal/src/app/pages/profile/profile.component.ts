import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { CandidateProfile, User, NotificationPreferences } from '@airral/shared-types';
import { catchError, finalize, of, timeout } from 'rxjs';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent implements OnInit {
  profile: CandidateProfile | null = null;
  loading = true;
  saving = false;
  successMessage = '';
  profileError = '';
  skillsText = '';
  targetRolesText = '';
  mustHaveSkillsText = '';
  niceToHaveSkillsText = '';
  avoidKeywordsText = '';

  // Notification preferences
  notificationPrefs: NotificationPreferences | null = null;
  notificationPrefsLoading = false;
  notificationSaveMessage = '';

  /** Second click required before a clear runs, so the button cannot be a slip. */
  clearConfirmPending = false;

  constructor(
    private readonly candidateApi: CandidatePortalService,
    private readonly auth: AuthService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    const user = this.auth.getCurrentUser();
    if (!user?.email) {
      this.profile = null;
      this.profileError = 'Sign in again to load your profile.';
      this.loading = false;
      return;
    }

    this.candidateApi.getCandidateProfile(user.email).pipe(
      timeout(12000),
      catchError(() => {
        this.profileError = 'Profile details are taking longer than expected. Showing account basics for now.';
        return of(this.profileFromUser(user));
      }),
      finalize(() => {
        this.loading = false;
        this.changeDetectorRef.markForCheck();
      })
    ).subscribe((profile) => {
      this.profile = this.normalizeProfile(profile, user);
      this.loadNotificationPreferences();
    });
  }

  private loadNotificationPreferences(): void {
    this.notificationPrefsLoading = true;
    this.candidateApi.getNotificationPreferences().pipe(
      catchError(() => of(null))
    ).subscribe((prefs) => {
      this.notificationPrefs = prefs;
      this.notificationPrefsLoading = false;
      this.changeDetectorRef.markForCheck();
    });
  }

  /**
   * Toggle one email preference, and only claim success if the server agrees.
   *
   * <p>"Preferences updated" used to be printed for any 200 response. The PUT
   * did not write anything, so the response carried the old values, and line
   * below rebinds from it -- meaning the toggle snapped back to where it was in
   * the same tick that the success text appeared. HTTP 200 is evidence that the
   * request was received, never that it took effect, so the message is now
   * gated on the response actually carrying the value that was asked for.
   *
   * <p>The wording of the failure is deliberately neutral. Two harmless races
   * can make the values disagree -- toggling the same switch twice quickly, and
   * a one-click unsubscribe landing between the write and the read -- so this
   * says the preference was not confirmed rather than asserting a failure, and
   * leaves the switch showing whatever the server last reported.
   */
  toggleNotification(key: keyof NotificationPreferences): void {
    if (!this.notificationPrefs) return;
    const current = this.notificationPrefs[key];
    const desired = !current;
    const update: Partial<NotificationPreferences> = { [key]: desired };
    this.notificationPrefs = { ...this.notificationPrefs, [key]: desired };
    this.candidateApi.updateNotificationPreferences(update).pipe(
      catchError(() => {
        // revert on error
        if (this.notificationPrefs) {
          this.notificationPrefs = { ...this.notificationPrefs, [key]: current };
        }
        return of(null);
      })
    ).subscribe((result) => {
      if (result) {
        this.notificationPrefs = result;
        this.notificationSaveMessage = result[key] === desired
          ? 'Preferences updated'
          : 'That preference did not save. Try again in a moment.';
      } else {
        this.notificationSaveMessage = 'That preference did not save. Try again in a moment.';
      }
      // Zoneless: a bare timer callback repaints nothing, so without this the
      // banner stayed on screen until an unrelated interaction nudged a pass --
      // the save() path below already learned this.
      setTimeout(() => {
        this.notificationSaveMessage = '';
        this.changeDetectorRef.markForCheck();
      }, 3000);
      this.changeDetectorRef.markForCheck();
    });
  }

  save(): void {
    if (!this.profile || this.saving) return;
    this.applyTextFieldsToProfile();
    this.saving = true;
    this.successMessage = '';
    this.clearConfirmPending = false;

    this.candidateApi.updateCandidateProfile(this.profile).subscribe({
      next: (p) => {
        this.profile = this.normalizeProfile(p, this.auth.getCurrentUser() || undefined);
        this.hydrateTextFields(this.profile);
        this.saving = false;
        this.profileError = '';
        this.successMessage = 'Profile saved — job matches will update when you return to Jobs.';
        // Signal the jobs page to refresh with new profile data
        localStorage.setItem('airral_profile_updated', Date.now().toString());
        setTimeout(() => {
          this.successMessage = '';
          this.changeDetectorRef.markForCheck();
        }, 4000);
        // Zoneless: nothing repaints off the back of an HTTP callback on its own,
        // so the saved state and the banner below stayed invisible until the next
        // unrelated interaction nudged a pass.
        this.changeDetectorRef.markForCheck();
      },
      error: () => {
        this.saving = false;
        this.profileError = 'Could not save profile. Try again in a moment.';
        this.changeDetectorRef.markForCheck();
      },
    });
  }

  /**
   * Empties the target-role list and saves it, which is a different request from
   * "I have not picked roles yet".
   *
   * <p>It goes through save() rather than its own call so it lands on the one
   * write path: the same merge rules, the same jobs-page refresh signal, the same
   * error handling. An empty list reaches the API explicitly, which is what lets
   * the backend record the choice instead of reading it as an unanswered question.
   */
  clearTargetRoles(): void {
    this.targetRolesText = '';
    this.save();
  }

  requestClearPreferences(): void {
    this.clearConfirmPending = true;
  }

  cancelClearPreferences(): void {
    this.clearConfirmPending = false;
  }

  /**
   * Clears the preferences that narrow or re-rank the feed, and only those.
   *
   * <p>Work authorization (needs sponsorship, needs E-Verify) is deliberately
   * left alone: those answers are facts about the candidate, not search
   * settings, and switching them off to widen a list would hand someone jobs
   * that cannot hire them. "Open to relocation" is left alone for the same
   * reason in reverse -- clearing it would narrow the feed, since the location
   * filter only stands down when it is on.
   *
   * <p>Salary is sent as 0 rather than null on purpose: null means "this update
   * does not mention the field" on the profile endpoint, so it was the reason an
   * emptied salary box never actually cleared. Zero is read as "no expectation"
   * and comes back as empty.
   */
  clearPreferences(): void {
    if (!this.profile) return;

    this.targetRolesText = '';
    this.mustHaveSkillsText = '';
    this.niceToHaveSkillsText = '';
    this.avoidKeywordsText = '';

    // '' is what the form's own "Any" option writes and what the API reads as
    // cleared; undefined would mean "this update does not mention the field" and
    // would leave the old value in place. The typed unions have no empty member,
    // which is why these go through a cast rather than a plain assignment.
    this.profile.preferredWorkMode = '' as unknown as CandidateProfile['preferredWorkMode'];
    this.profile.preferredEmploymentType = '' as unknown as CandidateProfile['preferredEmploymentType'];
    this.profile.salaryExpectationMin = 0;
    this.profile.salaryExpectationMax = 0;

    this.profile.matchPreferences = {
      ...(this.profile.matchPreferences ?? {}),
      salaryRequired: false,
      directCompanySourceOnly: false,
      easyApplyOnly: false,
    };

    this.save();
  }

  get hasSavedPreferences(): boolean {
    const preferences = this.profile?.matchPreferences ?? {};
    return Boolean(
      preferences.targetRoles?.length
      || preferences.mustHaveSkills?.length
      || preferences.niceToHaveSkills?.length
      || preferences.avoidKeywords?.length
      || preferences.salaryRequired
      || preferences.directCompanySourceOnly
      || preferences.easyApplyOnly
      || this.profile?.preferredWorkMode
      || this.profile?.preferredEmploymentType
      || this.profile?.salaryExpectationMin
      || this.profile?.salaryExpectationMax
    );
  }

  get completionPercent(): number {
    return this.profile?.profileCompletion ?? 0;
  }

  get initials(): string {
    if (!this.profile) return '?';
    const f = this.profile.firstName?.charAt(0) || '';
    const l = this.profile.lastName?.charAt(0) || '';
    return (f + l).toUpperCase() || '?';
  }

  get displayName(): string {
    const name = `${this.profile?.firstName || ''} ${this.profile?.lastName || ''}`.trim();
    return name || 'Applicant profile';
  }

  get resumeStatusLabel(): string {
    if (!this.profile?.activeResumeDocumentId && !this.profile?.resumeUrl) {
      return 'No resume uploaded';
    }

    if (this.profile.resumeParseStatus === 'PARSED') {
      return 'Resume parsed';
    }

    if (this.profile.resumeParseStatus === 'PARSE_FAILED') {
      return 'Resume needs review';
    }

    return 'Resume uploaded';
  }

  get resumeStatusIcon(): string {
    if (this.profile?.resumeParseStatus === 'PARSE_FAILED') {
      return 'error_outline';
    }

    return this.profile?.activeResumeDocumentId || this.profile?.resumeUrl ? 'description' : 'upload_file';
  }

  get searchStatusLabel(): string {
    return this.profile?.openToWork ? 'Actively searching' : 'Quiet mode';
  }

  get salaryRangeLabel(): string {
    const min = this.profile?.salaryExpectationMin;
    const max = this.profile?.salaryExpectationMax;
    if (min && max) {
      return `${this.formatCurrency(min)} - ${this.formatCurrency(max)}`;
    }
    if (min) {
      return `${this.formatCurrency(min)}+`;
    }
    if (max) {
      return `Up to ${this.formatCurrency(max)}`;
    }
    return 'Any range';
  }

  get workModeLabel(): string {
    return this.labelValue(this.profile?.preferredWorkMode, 'Any work mode');
  }

  get employmentTypeLabel(): string {
    return this.labelValue(this.profile?.preferredEmploymentType, 'Any employment');
  }

  get targetRolesPreview(): string {
    const roles = this.profile?.matchPreferences?.targetRoles ?? [];
    // "yet" read as an unfinished step, which is now the wrong story half the
    // time: an empty list can be a choice the user made on this page.
    return roles.length ? roles.slice(0, 3).join(', ') : 'No target roles saved';
  }

  get skillsPreview(): string {
    const skills = this.profile?.skills ?? [];
    return skills.length ? skills.slice(0, 4).join(', ') : 'No skills added';
  }

  get completionRingBackground(): string {
    const degrees = Math.max(0, Math.min(100, this.completionPercent)) * 3.6;
    return `conic-gradient(#4f46e5 ${degrees}deg, #e5e7eb ${degrees}deg)`;
  }

  private normalizeProfile(profile: CandidateProfile, user?: User): CandidateProfile {
    const normalized = {
      ...profile,
      email: profile.email || user?.email || '',
      firstName: profile.firstName || user?.firstName || '',
      lastName: profile.lastName || user?.lastName || '',
      skills: profile.skills ?? [],
      experience: profile.experience ?? [],
      education: profile.education ?? [],
      matchPreferences: profile.matchPreferences ?? {},
    };
    this.hydrateTextFields(normalized);
    return normalized;
  }

  private profileFromUser(user: User): CandidateProfile {
    return this.normalizeProfile({
      id: 0,
      userId: user.id,
      email: user.email,
      firstName: user.firstName || '',
      lastName: user.lastName || '',
      profileCompletion: 0,
      skills: [],
      experience: [],
      education: [],
      matchPreferences: {},
    }, user);
  }

  private hydrateTextFields(profile: CandidateProfile): void {
    this.skillsText = (profile.skills ?? []).join(', ');
    this.targetRolesText = (profile.matchPreferences?.targetRoles ?? []).join(', ');
    this.mustHaveSkillsText = (profile.matchPreferences?.mustHaveSkills ?? []).join(', ');
    this.niceToHaveSkillsText = (profile.matchPreferences?.niceToHaveSkills ?? []).join(', ');
    this.avoidKeywordsText = (profile.matchPreferences?.avoidKeywords ?? []).join(', ');
  }

  private applyTextFieldsToProfile(): void {
    if (!this.profile) {
      return;
    }

    this.profile.skills = this.parseList(this.skillsText);
    this.profile.matchPreferences = {
      ...(this.profile.matchPreferences ?? {}),
      targetRoles: this.parseList(this.targetRolesText),
      mustHaveSkills: this.parseList(this.mustHaveSkillsText),
      niceToHaveSkills: this.parseList(this.niceToHaveSkillsText),
      avoidKeywords: this.parseList(this.avoidKeywordsText),
    };
  }

  private parseList(value: string): string[] {
    return Array.from(new Set((value || '')
      .split(/[\n,]/)
      .map((item) => item.trim())
      .filter(Boolean)));
  }

  private formatCurrency(value: number): string {
    if (value >= 1000) {
      return `$${Math.round(value / 1000)}k`;
    }

    return `$${value}`;
  }

  private labelValue(value: string | undefined, fallback: string): string {
    if (!value) {
      return fallback;
    }

    return value.toLowerCase().split('_').map((part) => part.charAt(0).toUpperCase() + part.slice(1)).join(' ');
  }
}
