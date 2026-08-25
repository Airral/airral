import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { UpdateCandidateProfileRequest, ResumeHealthScore } from '@airral/shared-types';
import { markUserOnboarded } from '../../guards/onboarding.guard';
import { saveOnboardingJobSearchSeed } from '../../utils/job-search-seed';

type OnboardingStep = 1 | 2 | 3;

interface RoleOption {
  label: string;
  selected: boolean;
}

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.css',
})
export class OnboardingComponent {
  step: OnboardingStep = 1;
  saving = false;
  setupError = '';

  // Step 1 — Roles
  roleOptions: RoleOption[] = [
    { label: 'Software Engineer', selected: false },
    { label: 'Frontend Engineer', selected: false },
    { label: 'Backend Engineer', selected: false },
    { label: 'Full-Stack Engineer', selected: false },
    { label: 'Data Engineer', selected: false },
    { label: 'Data Scientist', selected: false },
    { label: 'Machine Learning Engineer', selected: false },
    { label: 'DevOps / SRE', selected: false },
    { label: 'Product Manager', selected: false },
    { label: 'Product Designer', selected: false },
    { label: 'UX Researcher', selected: false },
    { label: 'Engineering Manager', selected: false },
    { label: 'QA / Test Engineer', selected: false },
    { label: 'Mobile Engineer', selected: false },
    { label: 'Security Engineer', selected: false },
    { label: 'Solutions Engineer', selected: false },
    { label: 'Technical Writer', selected: false },
    { label: 'Business Analyst', selected: false },
    { label: 'Project Manager', selected: false },
    { label: 'Marketing', selected: false },
    { label: 'Sales / BDR', selected: false },
    { label: 'Customer Success', selected: false },
    { label: 'Operations', selected: false },
    { label: 'Finance / Accounting', selected: false },
  ];
  customRole = '';
  seniority = '';

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
    private readonly auth: AuthService
  ) {}

  // ── Step navigation ──────────────────────────────
  get canProceedStep1(): boolean {
    return this.selectedRoles.length > 0 && !!this.seniority;
  }

  get canProceedStep2(): boolean {
    return true; // resume is optional
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

  nextStep(): void {
    if (this.step === 1 && this.canProceedStep1) {
      this.step = 2;
    } else if (this.step === 2) {
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
        this.fetchResumeHealth();
      },
      error: () => {
        this.resumeError = 'Upload failed. Try a PDF or DOCX under 5 MB.';
        this.resumeUploading = false;
      },
    });
  }

  private fetchResumeHealth(): void {
    this.resumeHealthLoading = true;
    this.candidateApi.getResumeHealth().subscribe({
      next: (health) => {
        this.resumeHealth = health;
        this.resumeHealthLoading = false;
      },
      error: () => {
        this.resumeHealthLoading = false;
      },
    });
  }

  // ── Final submit ─────────────────────────────────
  finish(): void {
    if (this.saving) return;
    this.saving = true;
    this.setupError = '';

    const request: UpdateCandidateProfileRequest = {
      matchPreferences: {
        targetRoles: this.selectedRoles,
        seniority: this.seniority || undefined,
        needsSponsorship: this.needsSponsorship || undefined,
        searchStatus: 'ACTIVE',
      },
      preferredWorkMode: (this.workMode as any) || undefined,
      location: this.location.trim() || undefined,
      salaryExpectationMin: this.salaryMin ?? undefined,
      salaryExpectationMax: this.salaryMax ?? undefined,
    };

    this.candidateApi.updateCandidateProfile(request).subscribe({
      next: () => {
        const email = this.auth.getCurrentUser()?.email;
        markUserOnboarded(email);
        saveOnboardingJobSearchSeed(email, request);
        this.router.navigate(['/jobs'], { queryParams: { from: 'onboarding' } });
      },
      error: () => {
        this.setupError = 'We could not save your preferences yet. Please try again.';
        this.saving = false;
      },
    });
  }
}
