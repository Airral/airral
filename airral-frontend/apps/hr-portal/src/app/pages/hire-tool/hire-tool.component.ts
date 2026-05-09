import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { ApplicationApiService, JobApiService } from '@airral/shared-api';
import { Application, ApplicationStatus, Job } from '@airral/shared-types';

interface PipelineStage {
  key: ApplicationStatus;
  label: string;
  color: string;
}

@Component({
  selector: 'app-hire-tool',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './hire-tool.component.html',
  styleUrls: ['./hire-tool.component.css']
})
export class HireToolComponent implements OnInit {
  applications: Application[] = [];
  jobs: Job[] = [];
  selected: Application | null = null;
  selectedInterviewId: number | null = null;
  boardMode: 'stage' | 'team' = 'team';
  interviewDate = '';
  interviewNotes = '';
  feedback = '';
  rating = 3;
  loading = false;
  error = '';

  readonly pipeline: PipelineStage[] = [
    { key: ApplicationStatus.SUBMITTED, label: 'New', color: '#2196F3' },
    { key: ApplicationStatus.UNDER_REVIEW, label: 'Review', color: '#FF9800' },
    { key: ApplicationStatus.SHORTLISTED, label: 'Shortlist', color: '#8BC34A' },
    { key: ApplicationStatus.INTERVIEW_SCHEDULED, label: 'Interview', color: '#9C27B0' },
    { key: ApplicationStatus.INTERVIEWED, label: 'Interviewed', color: '#3F51B5' },
    { key: ApplicationStatus.OFFER_EXTENDED, label: 'Offer', color: '#009688' },
    { key: ApplicationStatus.HIRED, label: 'Hired', color: '#4CAF50' },
    { key: ApplicationStatus.REJECTED, label: 'Rejected', color: '#f44336' }
  ];

  constructor(
    private api: ApplicationApiService,
    private jobApi: JobApiService
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = '';

    forkJoin({
      applications: this.api.getAllApplications(),
      jobs: this.jobApi.getAllJobs(),
    }).subscribe({
      next: ({ applications, jobs }) => {
        this.applications = applications;
        this.jobs = jobs;
        this.loading = false;
      },
      error: (e) => {
        this.error = e.message;
        this.loading = false;
      }
    });
  }

  setBoardMode(mode: 'stage' | 'team'): void {
    this.boardMode = mode;
  }

  inStage(stage: ApplicationStatus): Application[] {
    return this.applications.filter(a => a.status === stage);
  }

  get teamColumns(): string[] {
    const departments = this.jobs.map((job) => job.department?.trim()).filter(Boolean) as string[];
    const unique = Array.from(new Set(departments));
    return unique.length ? unique : ['Unassigned'];
  }

  inTeam(team: string): Application[] {
    return this.applications.filter((app) => this.getTeamName(app) === team);
  }

  getTeamName(app: Application): string {
    return this.jobs.find((job) => job.id === app.jobId)?.department ?? 'Unassigned';
  }

  canReview(app: Application): boolean {
    return app.status === ApplicationStatus.SUBMITTED;
  }

  canShortlist(app: Application): boolean {
    return app.status === ApplicationStatus.UNDER_REVIEW;
  }

  canExtendOffer(app: Application): boolean {
    return app.status === ApplicationStatus.INTERVIEWED;
  }

  canHire(app: Application): boolean {
    return app.status === ApplicationStatus.OFFER_EXTENDED;
  }

  canReject(app: Application): boolean {
    return app.status !== ApplicationStatus.HIRED && app.status !== ApplicationStatus.REJECTED;
  }

  select(app: Application): void {
    this.selected = app;
    this.selectedInterviewId = null;

    if (app.status === ApplicationStatus.INTERVIEW_SCHEDULED || app.status === ApplicationStatus.INTERVIEWED) {
      this.api.getInterviewsByApplication(app.id).subscribe({
        next: (interviews) => {
          const latest = [...interviews].sort((a, b) => b.id - a.id)[0];
          this.selectedInterviewId = latest?.id ?? null;
        },
        error: () => {
          this.selectedInterviewId = null;
        },
      });
    }
  }

  advance(app: Application, nextStatus: ApplicationStatus): void {
    const previousStatus = app.status;
    app.status = nextStatus;

    this.api.updateApplicationStatus(app.id, nextStatus).subscribe({
      next: (updated) => {
        app.status = updated.status;
      },
      error: (e) => {
        app.status = previousStatus;
        this.error = e.message ?? 'Unable to update candidate stage. Please retry.';
      },
    });
  }

  schedule(): void {
    if (!this.selected || !this.interviewDate) return;
    const selected = this.selected;
    const previousStatus = selected.status;
    selected.status = ApplicationStatus.INTERVIEW_SCHEDULED;

    this.api.scheduleInterview(selected.id, this.interviewDate, this.interviewNotes).subscribe({
      next: (interview) => {
        this.selectedInterviewId = interview?.id ?? null;
        this.interviewDate = '';
        this.interviewNotes = '';
      },
      error: (e) => {
        selected.status = previousStatus;
        this.error = e.message ?? 'Unable to schedule interview. Please retry.';
      },
    });
  }

  submitFeedback(): void {
    if (!this.selectedInterviewId || !this.selected) {
      this.error = 'No interview selected for feedback.';
      return;
    }

    const previousStatus = this.selected.status;
    this.selected.status = ApplicationStatus.INTERVIEWED;

    this.api.submitInterviewFeedback(this.selectedInterviewId, this.feedback, this.rating).subscribe({
      next: () => {
        this.feedback = '';
      },
      error: (e) => {
        if (this.selected) {
          this.selected.status = previousStatus;
        }
        this.error = e.message ?? 'Unable to save interview feedback. Please retry.';
      },
    });
  }

  extendOffer(app: Application): void {
    const previousStatus = app.status;
    app.status = ApplicationStatus.OFFER_EXTENDED;

    this.api.extendOffer(app.id).subscribe({
      next: (updated) => {
        app.status = updated.status;
      },
      error: (e) => {
        app.status = previousStatus;
        this.error = e.message ?? 'Unable to extend offer. Please retry.';
      },
    });
  }

  hire(app: Application): void {
    if (!confirm(`Hire ${app.applicantEmail} for ${app.jobTitle}?`)) return;
    const previousStatus = app.status;
    app.status = ApplicationStatus.HIRED;

    this.api.hire(app.id).subscribe({
      next: (updated) => {
        app.status = updated.status;
      },
      error: (e) => {
        app.status = previousStatus;
        this.error = e.message ?? 'Unable to complete hire action. Please retry.';
      },
    });
  }

  reject(app: Application): void {
    if (!confirm(`Reject ${app.applicantEmail}?`)) return;
    const previousStatus = app.status;
    app.status = ApplicationStatus.REJECTED;

    this.api.reject(app.id).subscribe({
      next: (updated) => {
        app.status = updated.status;
      },
      error: (e) => {
        app.status = previousStatus;
        this.error = e.message ?? 'Unable to reject candidate. Please retry.';
      },
    });
  }

  stageColor(status: ApplicationStatus): string {
    return this.pipeline.find(s => s.key === status)?.color ?? '#999';
  }

  totalCandidates(): number {
    return this.applications.length;
  }

  interviewsInFlight(): number {
    return this.applications.filter((app) =>
      app.status === ApplicationStatus.INTERVIEW_SCHEDULED || app.status === ApplicationStatus.INTERVIEWED
    ).length;
  }

  offersInFlight(): number {
    return this.applications.filter((app) => app.status === ApplicationStatus.OFFER_EXTENDED).length;
  }

  hiresCount(): number {
    return this.applications.filter((app) => app.status === ApplicationStatus.HIRED).length;
  }

  getPurposeHint(app: Application): string {
    if (app.status === ApplicationStatus.SUBMITTED) return 'Review profile and move to Under Review.';
    if (app.status === ApplicationStatus.UNDER_REVIEW) return 'Shortlist if the profile matches role requirements.';
    if (app.status === ApplicationStatus.SHORTLISTED) return 'Schedule an interview and add panel notes.';
    if (app.status === ApplicationStatus.INTERVIEW_SCHEDULED) return 'Collect feedback after interview completes.';
    if (app.status === ApplicationStatus.INTERVIEWED) return 'Decide: extend offer or reject.';
    if (app.status === ApplicationStatus.OFFER_EXTENDED) return 'Track response and complete hire.';
    if (app.status === ApplicationStatus.HIRED) return 'Candidate is hired. Move to onboarding.';
    return 'Candidate closed. No action required.';
  }

  primaryActionLabel(app: Application): string | null {
    if (app.status === ApplicationStatus.SUBMITTED) return 'Move to Review';
    if (app.status === ApplicationStatus.UNDER_REVIEW) return 'Move to Shortlist';
    if (app.status === ApplicationStatus.INTERVIEWED) return 'Extend Offer';
    if (app.status === ApplicationStatus.OFFER_EXTENDED) return 'Hire';
    return null;
  }

  runPrimaryAction(app: Application): void {
    if (app.status === ApplicationStatus.SUBMITTED) {
      this.advance(app, ApplicationStatus.UNDER_REVIEW);
      return;
    }

    if (app.status === ApplicationStatus.UNDER_REVIEW) {
      this.advance(app, ApplicationStatus.SHORTLISTED);
      return;
    }

    if (app.status === ApplicationStatus.INTERVIEWED) {
      this.extendOffer(app);
      return;
    }

    if (app.status === ApplicationStatus.OFFER_EXTENDED) {
      this.hire(app);
    }
  }
}
