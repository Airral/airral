import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApplicationApiService, AuthApiService, HrEncounterApiService, JobApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { Application, ApplicationStatus, CreateEncounterRequest, HrEncounter, Job } from '@airral/shared-types';
import { catchError, combineLatest, finalize, of } from 'rxjs';
import { getPrimaryRole } from '../../feature-config';

interface CandidateDraft {
  jobId: string;
  name: string;
  email: string;
  phone: string;
  resumeUrl: string;
}

const EMPTY_DRAFT: CandidateDraft = { jobId: '', name: '', email: '', phone: '', resumeUrl: '' };

interface StageOption {
  value: 'ALL' | ApplicationStatus;
  label: string;
}

@Component({
  selector: 'app-hr-candidates',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './candidates.component.html',
  styleUrl: './candidates.component.css',
})
export class CandidatesComponent implements OnInit {
  private readonly applicationApi = inject(ApplicationApiService);
  private readonly jobApi = inject(JobApiService);
  private readonly encounterApi = inject(HrEncounterApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly authApi = inject(AuthApiService);

  /** HR adds candidates by hand. Hiring managers work the ones on their jobs. */
  readonly canAddCandidates = getPrimaryRole(this.auth.getCurrentUser()?.roles) === 'HR_MANAGER';
  /** False while AIRRAL has not verified the company, which is when candidate emails wait. Null until known. */
  companyVerified: boolean | null = null;

  readonly statuses = ApplicationStatus;
  readonly stageOptions: StageOption[] = [
    { value: 'ALL', label: 'All' },
    { value: ApplicationStatus.SUBMITTED, label: 'New' },
    { value: ApplicationStatus.UNDER_REVIEW, label: 'Review' },
    { value: ApplicationStatus.SHORTLISTED, label: 'Shortlist' },
    { value: ApplicationStatus.INTERVIEW_SCHEDULED, label: 'Interview' },
    { value: ApplicationStatus.INTERVIEWED, label: 'Decision' },
    { value: ApplicationStatus.OFFER_EXTENDED, label: 'Offer' },
    { value: ApplicationStatus.HIRED, label: 'Hired' },
    { value: ApplicationStatus.REJECTED, label: 'Closed' },
  ];

  applications: Application[] = [];
  jobs: Job[] = [];
  encounters: HrEncounter[] = [];
  selectedApplication: Application | null = null;
  selectedInterviewId: number | null = null;

  searchQuery = '';
  stageFilter: 'ALL' | ApplicationStatus = 'ALL';
  jobFilter = 'ALL';
  noteText = '';
  interviewDate = '';
  interviewNotes = '';
  feedback = '';
  rating = 3;
  emailInterview = true;

  addingCandidate = false;
  newCandidate: CandidateDraft = { ...EMPTY_DRAFT };
  confirmingReject = false;
  emailOnReject = true;

  loading = true;
  detailLoading = false;
  saving = false;
  error = '';
  success = '';

  ngOnInit(): void {
    const requestedStage = this.route.snapshot.queryParamMap.get('stage');
    if (requestedStage && Object.values(ApplicationStatus).includes(requestedStage as ApplicationStatus)) {
      this.stageFilter = requestedStage as ApplicationStatus;
    }
    this.load();
    this.authApi
      .me()
      .pipe(catchError(() => of(null)))
      .subscribe((status) => {
        this.companyVerified = status ? status.organizationVerificationStatus === 'VERIFIED' : null;
      });
  }

  load(): void {
    this.loading = true;
    this.error = '';
    combineLatest({
      applications: this.applicationApi.getAllApplications(),
      jobs: this.jobApi.getAllJobs(),
    })
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: ({ applications, jobs }) => {
          this.applications = [...applications].sort(
            (a, b) => new Date(b.appliedAt).getTime() - new Date(a.appliedAt).getTime(),
          );
          this.jobs = jobs;
          if (this.selectedApplication) {
            this.selectedApplication =
              this.applications.find((item) => item.id === this.selectedApplication?.id) ?? null;
          }
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to load candidates.';
        },
      });
  }

  get filteredApplications(): Application[] {
    const query = this.searchQuery.trim().toLowerCase();
    return this.applications.filter((application) => {
      const matchesStage = this.stageFilter === 'ALL' || application.status === this.stageFilter;
      const matchesJob = this.jobFilter === 'ALL' || String(application.jobId) === this.jobFilter;
      const matchesQuery =
        !query ||
        application.applicantName?.toLowerCase().includes(query) ||
        application.applicantEmail.toLowerCase().includes(query) ||
        this.jobTitle(application).toLowerCase().includes(query);
      return matchesStage && matchesJob && matchesQuery;
    });
  }

  get activeCount(): number {
    return this.applications.filter(
      (application) =>
        application.status !== ApplicationStatus.HIRED &&
        application.status !== ApplicationStatus.REJECTED &&
        application.status !== ApplicationStatus.WITHDRAWN,
    ).length;
  }

  get interviewCount(): number {
    return this.applications.filter(
      (application) =>
        application.status === ApplicationStatus.INTERVIEW_SCHEDULED ||
        application.status === ApplicationStatus.INTERVIEWED,
    ).length;
  }

  get offerCount(): number {
    return this.countForStage(ApplicationStatus.OFFER_EXTENDED);
  }

  countForStage(stage: 'ALL' | ApplicationStatus): number {
    if (stage === 'ALL') return this.applications.length;
    if (stage === ApplicationStatus.REJECTED) {
      return this.applications.filter(
        (application) =>
          application.status === ApplicationStatus.REJECTED ||
          application.status === ApplicationStatus.WITHDRAWN,
      ).length;
    }
    return this.applications.filter((application) => application.status === stage).length;
  }

  selectStage(stage: 'ALL' | ApplicationStatus): void {
    this.stageFilter = stage;
  }

  selectApplication(application: Application): void {
    this.selectedApplication = application;
    this.confirmingReject = false;
    this.detailLoading = true;
    this.error = '';
    this.success = '';
    this.encounters = [];
    this.selectedInterviewId = null;

    combineLatest({
      encounters: this.encounterApi
        .getEncountersByApplication(application.id)
        .pipe(catchError(() => of([] as HrEncounter[]))),
      interviews: this.applicationApi
        .getInterviewsByApplication(application.id)
        .pipe(catchError(() => of([]))),
    })
      .pipe(finalize(() => (this.detailLoading = false)))
      .subscribe(({ encounters, interviews }) => {
        this.encounters = [...encounters].sort(
          (a, b) => new Date(b.encounteredAt).getTime() - new Date(a.encounteredAt).getTime(),
        );
        this.selectedInterviewId = [...interviews].sort((a, b) => b.id - a.id)[0]?.id ?? null;
      });
  }

  closeDetail(): void {
    this.selectedApplication = null;
    this.encounters = [];
  }

  jobTitle(application: Application): string {
    return (
      application.jobTitle ||
      application.job?.title ||
      this.jobs.find((job) => job.id === application.jobId)?.title ||
      'Unknown position'
    );
  }

  stageLabel(status: ApplicationStatus): string {
    const labels: Record<ApplicationStatus, string> = {
      SUBMITTED: 'New',
      UNDER_REVIEW: 'In review',
      SHORTLISTED: 'Shortlisted',
      INTERVIEW_SCHEDULED: 'Interview scheduled',
      INTERVIEWED: 'Decision needed',
      OFFER_EXTENDED: 'Offer sent',
      HIRED: 'Hired',
      REJECTED: 'Rejected',
      WITHDRAWN: 'Withdrawn',
    };
    return labels[status];
  }

  initials(application: Application): string {
    const source = application.applicantName?.trim() || application.applicantEmail;
    return source
      .split(/[\s@._-]+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0].toUpperCase())
      .join('');
  }

  primaryActionLabel(application: Application): string | null {
    if (application.status === ApplicationStatus.SUBMITTED) return 'Start review';
    if (application.status === ApplicationStatus.UNDER_REVIEW) return 'Shortlist';
    if (application.status === ApplicationStatus.INTERVIEWED) return 'Move to offer';
    if (application.status === ApplicationStatus.OFFER_EXTENDED) return 'Mark hired';
    return null;
  }

  canOpenResume(application: Application): boolean {
    if (application.resumeOnFile) return true;
    try {
      const url = new URL(application.resumeUrl);
      return url.protocol === 'https:' || url.protocol === 'http:';
    } catch {
      return false;
    }
  }

  openResume(application: Application): void {
    if (application.resumeOnFile) {
      // An applicant's own resume comes from the API with this session, so it is
      // fetched and shown from memory. The tab opens first, inside the click, so
      // the browser does not block it as a pop-up.
      const tab = window.open('', '_blank');
      this.applicationApi.downloadResume(application.id).subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          if (tab) {
            tab.location.href = url;
          } else {
            window.open(url, '_blank');
          }
          setTimeout(() => URL.revokeObjectURL(url), 60_000);
        },
        error: () => {
          tab?.close();
          this.error = 'We could not open this resume. Try again.';
        },
      });
      return;
    }
    if (!this.canOpenResume(application)) {
      this.error = 'This resume is not available from the company workspace yet.';
      return;
    }
    window.open(application.resumeUrl, '_blank', 'noopener,noreferrer');
  }

  runPrimaryAction(application: Application): void {
    if (application.status === ApplicationStatus.SUBMITTED) {
      this.updateStatus(application, ApplicationStatus.UNDER_REVIEW);
    } else if (application.status === ApplicationStatus.UNDER_REVIEW) {
      this.updateStatus(application, ApplicationStatus.SHORTLISTED);
    } else if (application.status === ApplicationStatus.INTERVIEWED) {
      this.updateStatus(application, ApplicationStatus.OFFER_EXTENDED);
    } else if (application.status === ApplicationStatus.OFFER_EXTENDED) {
      this.updateStatus(application, ApplicationStatus.HIRED);
    }
  }

  updateStatus(application: Application, nextStatus: ApplicationStatus, notifyCandidate = false): void {
    if (this.saving || application.status === nextStatus) return;
    const previousStatus = application.status;
    this.saving = true;
    this.clearMessages();

    this.applicationApi
      .updateApplicationStatus(application.id, nextStatus, notifyCandidate)
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (updated) => {
          this.replaceApplication(updated);
          this.success = `Candidate moved to ${this.stageLabel(updated.status)}.`;
          this.recordEncounter({
            encounterType: 'STATUS_CHANGE',
            title: `Moved to ${this.stageLabel(updated.status)}`,
            description: `${this.stageLabel(previousStatus)} to ${this.stageLabel(updated.status)}`,
            applicationId: updated.id,
            jobId: updated.jobId,
            candidateId: updated.applicantId,
          });
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to update candidate stage.';
        },
      });
  }

  reject(): void {
    this.clearMessages();
    this.emailOnReject = true;
    this.confirmingReject = true;
  }

  cancelReject(): void {
    this.confirmingReject = false;
  }

  confirmReject(application: Application): void {
    this.confirmingReject = false;
    this.updateStatus(application, ApplicationStatus.REJECTED, this.emailOnReject);
  }

  firstName(application: Application): string {
    return application.applicantName?.trim().split(/\s+/)[0] || 'the candidate';
  }

  openAddCandidate(): void {
    this.clearMessages();
    this.addingCandidate = true;
    if (!this.newCandidate.jobId) {
      if (this.jobFilter !== 'ALL') this.newCandidate.jobId = this.jobFilter;
      else if (this.jobs.length === 1) this.newCandidate.jobId = String(this.jobs[0].id);
    }
  }

  cancelAddCandidate(): void {
    this.addingCandidate = false;
    this.newCandidate = { ...EMPTY_DRAFT };
  }

  get canSubmitCandidate(): boolean {
    const draft = this.newCandidate;
    return !this.saving && !!draft.jobId && !!draft.name.trim() && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(draft.email.trim());
  }

  /** Someone HR found itself. They get no AIRRAL account and no email saying they were added. */
  addCandidate(): void {
    if (!this.canSubmitCandidate) return;
    const draft = this.newCandidate;
    this.saving = true;
    this.clearMessages();

    this.applicationApi
      .submitApplication({
        jobId: Number(draft.jobId),
        applicantName: draft.name.trim(),
        applicantEmail: draft.email.trim(),
        applicantPhone: draft.phone.trim() || undefined,
        resumeUrl: draft.resumeUrl.trim() || undefined,
      })
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (created) => {
          this.applications = [created, ...this.applications];
          this.addingCandidate = false;
          this.newCandidate = { ...EMPTY_DRAFT };
          this.selectApplication(created);
          this.success = `${created.applicantName || created.applicantEmail} is now a candidate for ${this.jobTitle(created)}.`;
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to add this candidate.';
        },
      });
  }

  scheduleInterview(): void {
    if (!this.selectedApplication || !this.interviewDate || this.saving) return;
    const application = this.selectedApplication;
    const notes = this.interviewNotes.trim() || undefined;
    this.saving = true;
    this.clearMessages();

    this.applicationApi
      .scheduleInterview(application.id, this.interviewDate, notes, this.emailInterview)
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (interview) => {
          application.status = ApplicationStatus.INTERVIEW_SCHEDULED;
          this.selectedInterviewId = interview.id;
          this.interviewDate = '';
          this.interviewNotes = '';
          this.success = 'Interview scheduled.';
          this.recordEncounter({
            encounterType: 'INTERVIEW_SCHEDULED',
            title: 'Interview scheduled',
            description: new Date(interview.interviewDate).toLocaleString(),
            notes,
            applicationId: application.id,
            jobId: application.jobId,
            candidateId: application.applicantId,
            interviewId: interview.id,
          });
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to schedule interview.';
        },
      });
  }

  submitFeedback(): void {
    if (!this.selectedApplication || !this.selectedInterviewId || !this.feedback.trim() || this.saving) return;
    const application = this.selectedApplication;
    const feedback = this.feedback.trim();
    const interviewId = this.selectedInterviewId;
    this.saving = true;
    this.clearMessages();

    this.applicationApi
      .submitInterviewFeedback(interviewId, feedback, this.rating)
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: () => {
          application.status = ApplicationStatus.INTERVIEWED;
          this.feedback = '';
          this.success = 'Interview feedback saved.';
          this.recordEncounter({
            encounterType: 'INTERVIEW_FEEDBACK',
            title: 'Interview feedback added',
            notes: feedback,
            rating: this.rating,
            applicationId: application.id,
            jobId: application.jobId,
            candidateId: application.applicantId,
            interviewId,
          });
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to save interview feedback.';
        },
      });
  }

  addNote(): void {
    if (!this.selectedApplication || !this.noteText.trim() || this.saving) return;
    const application = this.selectedApplication;
    const notes = this.noteText.trim();
    this.saving = true;
    this.clearMessages();

    this.encounterApi
      .createEncounter({
        encounterType: 'NOTE',
        title: 'Candidate note',
        notes,
        applicationId: application.id,
        jobId: application.jobId,
        candidateId: application.applicantId,
      })
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (encounter) => {
          this.encounters = [encounter, ...this.encounters];
          this.noteText = '';
          this.success = 'Note saved to candidate history.';
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to save note.';
        },
      });
  }

  private replaceApplication(updated: Application): void {
    this.applications = this.applications.map((application) =>
      application.id === updated.id ? { ...application, ...updated } : application,
    );
    if (this.selectedApplication?.id === updated.id) {
      this.selectedApplication = this.applications.find((application) => application.id === updated.id) ?? null;
    }
  }

  private recordEncounter(request: CreateEncounterRequest): void {
    this.encounterApi.createEncounter(request).subscribe({
      next: (encounter) => {
        this.encounters = [encounter, ...this.encounters];
      },
      error: () => {
        this.error = 'The stage changed, but its timeline entry could not be saved.';
      },
    });
  }

  private clearMessages(): void {
    this.error = '';
    this.success = '';
  }
}
