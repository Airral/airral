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

  toggleNotification(key: keyof NotificationPreferences): void {
    if (!this.notificationPrefs) return;
    const current = this.notificationPrefs[key];
    const update: Partial<NotificationPreferences> = { [key]: !current };
    this.notificationPrefs = { ...this.notificationPrefs, [key]: !current };
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
        this.notificationSaveMessage = 'Preferences updated';
        setTimeout(() => (this.notificationSaveMessage = ''), 2000);
      }
      this.changeDetectorRef.markForCheck();
    });
  }

  save(): void {
    if (!this.profile || this.saving) return;
    this.applyTextFieldsToProfile();
    this.saving = true;
    this.successMessage = '';

    this.candidateApi.updateCandidateProfile(this.profile).subscribe({
      next: (p) => {
        this.profile = this.normalizeProfile(p, this.auth.getCurrentUser() || undefined);
        this.hydrateTextFields(this.profile);
        this.saving = false;
        this.profileError = '';
        this.successMessage = 'Profile saved — job matches will update when you return to Jobs.';
        // Signal the jobs page to refresh with new profile data
        localStorage.setItem('airral_profile_updated', Date.now().toString());
        setTimeout(() => (this.successMessage = ''), 4000);
      },
      error: () => {
        this.saving = false;
        this.profileError = 'Could not save profile. Try again in a moment.';
      },
    });
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
    return roles.length ? roles.slice(0, 3).join(', ') : 'No target roles yet';
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
