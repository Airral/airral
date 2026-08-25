import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import {
  CandidateEducationEntry,
  CandidateExperienceEntry,
  CandidateProfile,
  CandidateResumeReview,
  ResumeHealthScore,
  UpdateCandidateProfileRequest,
} from '@airral/shared-types';
import { catchError, finalize, forkJoin, of, switchMap, timeout } from 'rxjs';

@Component({
  selector: 'app-resume',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule],
  templateUrl: './resume.component.html',
  styleUrl: './resume.component.css',
})
export class ResumeComponent implements OnInit {
  profile: CandidateProfile | null = null;
  review: CandidateResumeReview | null = null;
  health: ResumeHealthScore | null = null;

  loading = true;
  uploading = false;
  saving = false;
  dirty = false;
  errorMessage = '';
  successMessage = '';

  headline = '';
  summary = '';
  location = '';
  workMode = '';
  skills: string[] = [];
  targetRoles: string[] = [];
  experience: CandidateExperienceEntry[] = [];
  education: CandidateEducationEntry[] = [];
  skillInput = '';
  roleInput = '';

  constructor(
    private readonly candidateApi: CandidatePortalService,
    private readonly auth: AuthService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    const email = this.auth.getCurrentUser()?.email;
    if (!email) {
      this.errorMessage = 'Sign in again to review your resume.';
      this.loading = false;
      return;
    }

    this.candidateApi.getCandidateProfile(email).pipe(
      timeout(12000),
      catchError(() => {
        this.errorMessage = 'Could not load your resume workspace. Try again in a moment.';
        return of(null);
      })
    ).subscribe((profile) => {
      this.profile = profile;
      if (this.hasResume) {
        this.loadAnalysis();
      } else {
        this.loading = false;
        this.changeDetectorRef.markForCheck();
      }
    });
  }

  onResumeSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.uploading) return;

    const extension = file.name.split('.').pop()?.toLowerCase();
    if (!extension || !['pdf', 'docx'].includes(extension)) {
      this.errorMessage = 'Choose a PDF or DOCX resume.';
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      this.errorMessage = 'Resume files must be 5 MB or smaller.';
      return;
    }

    this.uploading = true;
    this.errorMessage = '';
    this.successMessage = '';

    this.candidateApi.uploadCandidateResume(file).pipe(
      switchMap((profile) => {
        this.profile = profile;
        return this.fetchAnalysis();
      }),
      finalize(() => {
        this.uploading = false;
        this.changeDetectorRef.markForCheck();
      })
    ).subscribe({
      next: ({ review, health }) => {
        if (!review) {
          this.errorMessage = 'The resume uploaded, but AIRRAL could not read its structured details.';
          return;
        }
        this.review = this.normalizeReview(review);
        this.health = health;
        this.hydrateEditor();
        this.successMessage = 'Resume parsed. Review the extracted details before saving.';
      },
      error: () => {
        this.errorMessage = 'Could not upload this resume. Check the file and try again.';
      },
    });
  }

  saveReview(): void {
    if (!this.profile || !this.review || this.saving) return;

    const skills = this.cleanList(this.skills);
    const targetRoles = this.cleanList(this.targetRoles);
    const experience = this.cleanExperience();
    const education = this.cleanEducation();

    if (!this.headline.trim() && targetRoles.length === 0) {
      this.errorMessage = 'Add a headline or at least one target role before saving.';
      return;
    }

    const request: UpdateCandidateProfileRequest = {
      headline: this.headline.trim(),
      bio: this.summary.trim(),
      location: this.location.trim(),
      preferredWorkMode: this.workMode,
      skills,
      experience,
      education,
      matchPreferences: {
        ...(this.profile.matchPreferences ?? {}),
        targetRoles,
      },
    };

    this.saving = true;
    this.errorMessage = '';
    this.successMessage = '';
    this.candidateApi.updateCandidateProfile(request).pipe(
      finalize(() => {
        this.saving = false;
        this.changeDetectorRef.markForCheck();
      })
    ).subscribe({
      next: (profile) => {
        this.profile = profile;
        this.skills = skills;
        this.targetRoles = targetRoles;
        this.experience = experience;
        this.education = education;
        this.dirty = false;
        this.successMessage = 'Verified resume details saved. Job matches will use this version.';
        localStorage.setItem('airral_profile_updated', Date.now().toString());
      },
      error: () => {
        this.errorMessage = 'Could not save your reviewed resume. Try again in a moment.';
      },
    });
  }

  addSkill(): void {
    this.skills = this.addTag(this.skills, this.skillInput);
    if (this.skillInput.trim()) this.markDirty();
    this.skillInput = '';
  }

  addRole(): void {
    this.targetRoles = this.addTag(this.targetRoles, this.roleInput);
    if (this.roleInput.trim()) this.markDirty();
    this.roleInput = '';
  }

  handleTagKeydown(event: KeyboardEvent, type: 'skill' | 'role'): void {
    if (event.key !== 'Enter' && event.key !== ',') return;
    event.preventDefault();
    if (type === 'skill') {
      this.addSkill();
    } else {
      this.addRole();
    }
  }

  removeSkill(index: number): void {
    this.skills = this.skills.filter((_, itemIndex) => itemIndex !== index);
    this.markDirty();
  }

  removeRole(index: number): void {
    this.targetRoles = this.targetRoles.filter((_, itemIndex) => itemIndex !== index);
    this.markDirty();
  }

  addExperience(): void {
    this.experience = [
      ...this.experience,
      { company: '', title: '', startDate: '', endDate: '', description: '', current: false },
    ];
    this.markDirty();
  }

  removeExperience(index: number): void {
    this.experience = this.experience.filter((_, itemIndex) => itemIndex !== index);
    this.markDirty();
  }

  toggleCurrent(entry: CandidateExperienceEntry): void {
    if (entry.current) entry.endDate = undefined;
    this.markDirty();
  }

  addEducation(): void {
    this.education = [...this.education, { school: '', degree: '', field: '' }];
    this.markDirty();
  }

  removeEducation(index: number): void {
    this.education = this.education.filter((_, itemIndex) => itemIndex !== index);
    this.markDirty();
  }

  setWorkMode(mode: string): void {
    this.workMode = mode;
    this.markDirty();
  }

  markDirty(): void {
    this.dirty = true;
    this.successMessage = '';
  }

  get hasResume(): boolean {
    return Boolean(this.profile?.activeResumeDocumentId || this.profile?.resumeUrl);
  }

  get confidenceScore(): number {
    return Math.max(0, Math.min(100, Math.round(this.review?.parseConfidenceScore ?? 0)));
  }

  get confidenceLabel(): string {
    if (this.confidenceScore >= 80) return 'High confidence';
    if (this.confidenceScore >= 60) return 'Review suggested';
    return 'Needs review';
  }

  get confidenceClass(): string {
    if (this.confidenceScore >= 80) return 'good';
    if (this.confidenceScore >= 60) return 'mid';
    return 'low';
  }

  get sectionCount(): number {
    return [this.headline, this.summary, this.skills.length, this.experience.length, this.education.length]
      .filter(Boolean).length;
  }

  trackByIndex(index: number): number {
    return index;
  }

  private loadAnalysis(): void {
    this.fetchAnalysis().pipe(
      finalize(() => {
        this.loading = false;
        this.changeDetectorRef.markForCheck();
      })
    ).subscribe(({ review, health }) => {
      this.review = review ? this.normalizeReview(review) : null;
      this.health = health;
      if (this.review) {
        this.hydrateEditor();
      } else {
        this.errorMessage = 'Your resume is available, but its extracted details could not be loaded.';
      }
    });
  }

  private fetchAnalysis() {
    return forkJoin({
      review: this.candidateApi.getResumeReview().pipe(catchError(() => of(null))),
      health: this.candidateApi.getResumeHealth().pipe(catchError(() => of(null))),
    });
  }

  private normalizeReview(review: CandidateResumeReview): CandidateResumeReview {
    return {
      ...review,
      skills: review.skills ?? [],
      experience: review.experience ?? [],
      education: review.education ?? [],
      suggestedTargetRoles: review.suggestedTargetRoles ?? [],
      parseWarnings: review.parseWarnings ?? [],
    };
  }

  private hydrateEditor(): void {
    if (!this.review) return;
    this.headline = this.review.headline || this.profile?.headline || '';
    this.summary = this.review.summary || this.profile?.bio || '';
    this.location = this.review.location || this.profile?.location || '';
    this.workMode = this.profile?.preferredWorkMode || this.review.suggestedWorkMode || '';
    this.skills = this.cleanList(this.review.skills.length ? this.review.skills : this.profile?.skills ?? []);
    this.targetRoles = this.cleanList([
      ...(this.profile?.matchPreferences?.targetRoles ?? []),
      ...this.review.suggestedTargetRoles,
    ]);
    this.experience = (this.review.experience.length ? this.review.experience : this.profile?.experience ?? [])
      .map((entry) => ({ ...entry }));
    this.education = (this.review.education.length ? this.review.education : this.profile?.education ?? [])
      .map((entry) => ({ ...entry }));
    this.dirty = false;
  }

  private addTag(values: string[], input: string): string[] {
    const additions = input.split(',').map((value) => value.trim()).filter(Boolean);
    return this.cleanList([...values, ...additions]);
  }

  private cleanList(values: string[]): string[] {
    const seen = new Set<string>();
    return values.map((value) => value.trim()).filter((value) => {
      const key = value.toLowerCase();
      if (!value || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  private cleanExperience(): CandidateExperienceEntry[] {
    return this.experience
      .map((entry) => ({
        company: entry.company?.trim() ?? '',
        title: entry.title?.trim() ?? '',
        startDate: entry.startDate?.trim() ?? '',
        endDate: entry.current ? undefined : entry.endDate?.trim() || undefined,
        description: entry.description?.trim() || undefined,
        current: Boolean(entry.current),
      }))
      .filter((entry) => Boolean(entry.company || entry.title));
  }

  private cleanEducation(): CandidateEducationEntry[] {
    return this.education
      .map((entry) => ({
        school: entry.school?.trim() ?? '',
        degree: entry.degree?.trim() ?? '',
        field: entry.field?.trim() ?? '',
        graduationYear: entry.graduationYear || undefined,
      }))
      .filter((entry) => Boolean(entry.school || entry.degree || entry.field));
  }
}
