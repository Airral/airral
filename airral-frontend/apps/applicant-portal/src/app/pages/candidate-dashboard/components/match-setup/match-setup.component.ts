import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { CandidateMatchPreferences, CandidateProfile, UpdateCandidateProfileRequest } from '@airral/shared-types';

type WorkModeOption = 'REMOTE' | 'HYBRID' | 'ONSITE';
type EmploymentTypeOption = 'FULL_TIME' | 'CONTRACT' | 'INTERNSHIP';
type SeniorityOption = 'MID' | 'SENIOR' | 'STAFF' | 'LEAD';
type SearchStatusOption = 'ACTIVE' | 'OPEN' | 'CASUAL';
type MatchRuleKey = 'salaryRequired' | 'easyApplyOnly' | 'noTakeHome' | 'needsSponsorship' | 'directCompanySourceOnly' | 'stabilityFirst';

interface MatchSetupDraft {
  targetRolesText: string;
  seniority: SeniorityOption;
  searchStatus: SearchStatusOption;
  location: string;
  workMode: WorkModeOption;
  employmentType: EmploymentTypeOption;
  salaryMin: number | null;
  salaryMax: number | null;
  skillsText: string;
  niceSkillsText: string;
  avoidText: string;
  resumeUrl: string;
  salaryRequired: boolean;
  easyApplyOnly: boolean;
  noTakeHome: boolean;
  needsSponsorship: boolean;
  directCompanySourceOnly: boolean;
  stabilityFirst: boolean;
  openToRelocation: boolean;
}

@Component({
  selector: 'app-match-setup',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatIconModule],
  templateUrl: './match-setup.component.html',
  styleUrl: './match-setup.component.css'
})
export class MatchSetupComponent implements OnChanges {
  @Input() profile: CandidateProfile | null = null;
  @Input() saving = false;
  @Input() resumeUploading = false;
  @Input() resumeUploadError = '';
  @Output() saveSetup = new EventEmitter<UpdateCandidateProfileRequest>();
  @Output() resumeUpload = new EventEmitter<File>();
  @Output() skipSetup = new EventEmitter<void>();

  draft: MatchSetupDraft = {
    targetRolesText: '',
    seniority: 'SENIOR',
    searchStatus: 'ACTIVE',
    location: '',
    workMode: 'REMOTE',
    employmentType: 'FULL_TIME',
    salaryMin: null,
    salaryMax: null,
    skillsText: '',
    niceSkillsText: '',
    avoidText: '',
    resumeUrl: '',
    salaryRequired: true,
    easyApplyOnly: false,
    noTakeHome: false,
    needsSponsorship: false,
    directCompanySourceOnly: true,
    stabilityFirst: false,
    openToRelocation: false,
  };

  readonly roleSuggestions = ['Frontend Engineer', 'Full Stack Engineer', 'UI Engineer', 'Design Systems Engineer'];

  readonly seniorityOptions: Array<{ value: SeniorityOption; label: string }> = [
    { value: 'MID', label: 'Mid' },
    { value: 'SENIOR', label: 'Senior' },
    { value: 'STAFF', label: 'Staff' },
    { value: 'LEAD', label: 'Lead' },
  ];

  readonly searchStatuses: Array<{ value: SearchStatusOption; label: string; description: string }> = [
    { value: 'ACTIVE', label: 'Applying now', description: 'Rank ready-to-apply roles first' },
    { value: 'OPEN', label: 'Open', description: 'Show strong matches without urgency' },
    { value: 'CASUAL', label: 'Casual', description: 'Prioritize exceptional opportunities' },
  ];

  readonly workModes: Array<{ value: WorkModeOption; label: string; icon: string }> = [
    { value: 'REMOTE', label: 'Remote', icon: 'public' },
    { value: 'HYBRID', label: 'Hybrid', icon: 'hub' },
    { value: 'ONSITE', label: 'On-site', icon: 'apartment' },
  ];

  readonly employmentTypes: Array<{ value: EmploymentTypeOption; label: string }> = [
    { value: 'FULL_TIME', label: 'Full-time' },
    { value: 'CONTRACT', label: 'Contract' },
    { value: 'INTERNSHIP', label: 'Internship' },
  ];

  readonly matchRules: Array<{ key: MatchRuleKey; label: string; icon: string }> = [
    { key: 'salaryRequired', label: 'Salary listed', icon: 'payments' },
    { key: 'easyApplyOnly', label: 'Easy apply first', icon: 'bolt' },
    { key: 'noTakeHome', label: 'Avoid take-homes', icon: 'assignment_late' },
    { key: 'needsSponsorship', label: 'Sponsorship friendly', icon: 'verified_user' },
    { key: 'directCompanySourceOnly', label: 'Company source', icon: 'domain' },
    { key: 'stabilityFirst', label: 'Stability signal', icon: 'shield' },
  ];

  private initialized = false;

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['profile'] || !this.profile) {
      return;
    }

    if (this.initialized) {
      if (this.profile.resumeUrl) {
        this.draft.resumeUrl = this.profile.resumeUrl;
      }
      return;
    }

    const preferences = this.profile.matchPreferences;

    this.draft = {
      targetRolesText: preferences?.targetRoles?.length ? preferences.targetRoles.join(', ') : this.profile.headline || '',
      seniority: this.toSeniority(preferences?.seniority),
      searchStatus: this.toSearchStatus(preferences?.searchStatus),
      location: this.profile.location || '',
      workMode: this.toWorkMode(this.profile.preferredWorkMode),
      employmentType: this.toEmploymentType(this.profile.preferredEmploymentType),
      salaryMin: this.profile.salaryExpectationMin ?? null,
      salaryMax: this.profile.salaryExpectationMax ?? null,
      skillsText: preferences?.mustHaveSkills?.length ? preferences.mustHaveSkills.join(', ') : (this.profile.skills || []).join(', '),
      niceSkillsText: (preferences?.niceToHaveSkills || []).join(', '),
      avoidText: (preferences?.avoidKeywords || []).join(', '),
      resumeUrl: this.profile.resumeUrl || '',
      salaryRequired: preferences?.salaryRequired ?? true,
      easyApplyOnly: preferences?.easyApplyOnly ?? false,
      noTakeHome: preferences?.noTakeHome ?? false,
      needsSponsorship: preferences?.needsSponsorship ?? false,
      directCompanySourceOnly: preferences?.directCompanySourceOnly ?? true,
      stabilityFirst: preferences?.stabilityFirst ?? false,
      openToRelocation: preferences?.openToRelocation ?? false,
    };
    this.initialized = true;
  }

  handleResumeFileChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }

    this.resumeUpload.emit(file);
    input.value = '';
  }

  get completedSignals(): number {
    return [
      this.targetRoles.length > 0,
      Boolean(this.draft.seniority && this.draft.searchStatus),
      Boolean(this.draft.location.trim() && this.draft.workMode),
      Boolean(this.draft.salaryMin && this.draft.salaryMax),
      this.mustHaveSkills.length > 0,
      Boolean(this.draft.resumeUrl.trim()) || this.niceToHaveSkills.length > 0 || this.avoidKeywords.length > 0,
    ].filter(Boolean).length;
  }

  get totalSignals(): number {
    return 6;
  }

  get completionPercent(): number {
    return Math.round((this.completedSignals / this.totalSignals) * 100);
  }

  get targetRoles(): string[] {
    return this.parseList(this.draft.targetRolesText).slice(0, 5);
  }

  get mustHaveSkills(): string[] {
    return this.parseList(this.draft.skillsText).slice(0, 10);
  }

  get niceToHaveSkills(): string[] {
    return this.parseList(this.draft.niceSkillsText).slice(0, 10);
  }

  get avoidKeywords(): string[] {
    return this.parseList(this.draft.avoidText).slice(0, 8);
  }

  get skills(): string[] {
    return this.uniqueList([...this.mustHaveSkills, ...this.niceToHaveSkills]).slice(0, 16);
  }

  get activeRules(): Array<{ key: MatchRuleKey; label: string; icon: string }> {
    return this.matchRules.filter((rule) => this.draft[rule.key]);
  }

  get canSubmit(): boolean {
    return Boolean(this.targetRoles.length > 0 && this.draft.location.trim()) && !this.saving;
  }

  addRoleSuggestion(role: string): void {
    const roles = this.uniqueList([...this.targetRoles, role]);
    this.draft.targetRolesText = roles.join(', ');
  }

  setSeniority(seniority: SeniorityOption): void {
    this.draft.seniority = seniority;
  }

  setSearchStatus(searchStatus: SearchStatusOption): void {
    this.draft.searchStatus = searchStatus;
  }

  private parseList(value: string): string[] {
    return value
      .split(',')
      .flatMap((part) => part.split('\n'))
      .map((item) => item.trim())
      .filter(Boolean)
      .filter((item, index, list) => list.findIndex((other) => other.toLowerCase() === item.toLowerCase()) === index);
  }

  private uniqueList(values: string[]): string[] {
    return values.filter((value, index, list) => list.findIndex((other) => other.toLowerCase() === value.toLowerCase()) === index);
  }

  setWorkMode(workMode: WorkModeOption): void {
    this.draft.workMode = workMode;
  }

  setEmploymentType(employmentType: EmploymentTypeOption): void {
    this.draft.employmentType = employmentType;
  }

  toggleRule(key: MatchRuleKey): void {
    this.draft[key] = !this.draft[key];
  }

  toggleRelocation(): void {
    this.draft.openToRelocation = !this.draft.openToRelocation;
  }

  submit(): void {
    if (!this.canSubmit) {
      return;
    }

    const matchPreferences: CandidateMatchPreferences = {
      targetRoles: this.targetRoles,
      seniority: this.draft.seniority,
      searchStatus: this.draft.searchStatus,
      needsSponsorship: this.draft.needsSponsorship,
      openToRelocation: this.draft.openToRelocation,
      salaryRequired: this.draft.salaryRequired,
      easyApplyOnly: this.draft.easyApplyOnly,
      noTakeHome: this.draft.noTakeHome,
      directCompanySourceOnly: this.draft.directCompanySourceOnly,
      stabilityFirst: this.draft.stabilityFirst,
      mustHaveSkills: this.mustHaveSkills,
      niceToHaveSkills: this.niceToHaveSkills,
      avoidKeywords: this.avoidKeywords,
    };

    this.saveSetup.emit({
      headline: this.targetRoles[0],
      location: this.draft.location.trim(),
      preferredWorkMode: this.draft.workMode,
      preferredEmploymentType: this.draft.employmentType,
      salaryExpectationMin: this.draft.salaryMin ?? undefined,
      salaryExpectationMax: this.draft.salaryMax ?? undefined,
      salaryCurrency: 'USD',
      skills: this.skills,
      resumeUrl: this.draft.resumeUrl.trim() || undefined,
      openToWork: true,
      matchPreferences,
    });
  }

  private toWorkMode(workMode?: string): WorkModeOption {
    return workMode === 'HYBRID' || workMode === 'ONSITE' ? workMode : 'REMOTE';
  }

  private toEmploymentType(employmentType?: string): EmploymentTypeOption {
    return employmentType === 'CONTRACT' || employmentType === 'INTERNSHIP' ? employmentType : 'FULL_TIME';
  }

  private toSeniority(seniority?: string): SeniorityOption {
    return seniority === 'MID' || seniority === 'STAFF' || seniority === 'LEAD' ? seniority : 'SENIOR';
  }

  private toSearchStatus(searchStatus?: string): SearchStatusOption {
    return searchStatus === 'OPEN' || searchStatus === 'CASUAL' ? searchStatus : 'ACTIVE';
  }
}
