import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { finalize, timeout } from 'rxjs/operators';
import { ApplicationApiService, JobApiService } from '@airral/shared-api';
import { Application, ApplicationStatus, CreateJobRequest, Job, JobStatus } from '@airral/shared-types';
import { JobDialogComponent, JobFormData } from './job-dialog/job-dialog.component';

@Component({
  selector: 'app-hr-jobs',
  standalone: true,
  imports: [CommonModule, FormsModule, JobDialogComponent],
  templateUrl: './jobs.component.html',
  styleUrl: './jobs.component.css',
})
export class JobsComponent implements OnInit {
  readonly jobStatus = JobStatus;

  jobs: Job[] = [];
  applications: Application[] = [];

  loading = false;
  saving = false;
  error = '';

  showForm = false;
  editingJobId: number | null = null;

  // LinkedIn Integration (mock for now - will come from organization settings)
  linkedInConnected = true;  // Simulating LinkedIn is connected

  // Pagination
  currentPage = 1;
  pageSize = 20;

  form: JobFormData = {
    title: '',
    department: '',
    location: '',
    employmentType: 'Full-time',
    salaryMin: '',
    salaryMax: '',
    description: '',
    requirements: '',
    niceToHave: '',
    atsKeywords: '',
    linkedInEnabled: false,
  };

  constructor(
    private readonly jobApi: JobApiService,
    private readonly applicationApi: ApplicationApiService
  ) {}

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.error = '';

    forkJoin({
      jobs: this.jobApi.getAllJobs(),
      applications: this.applicationApi.getAllApplications(),
    }).pipe(
      timeout(15000),
      finalize(() => {
        this.loading = false;
      })
    ).subscribe({
      next: ({ jobs, applications }) => {
        this.jobs = [...jobs].sort((a, b) => b.id - a.id);
        this.applications = applications;
      },
      error: (err) => {
        this.jobs = [];
        this.applications = [];
        this.error = '';
      },
    });
  }

  openCreateForm(): void {
    this.showForm = true;
    this.editingJobId = null;
    this.form = {
      title: '',
      department: '',
      location: '',
      employmentType: 'Full-time',
      salaryMin: '',
      salaryMax: '',
      description: '',
      requirements: '',
      niceToHave: '',
      atsKeywords: '',
      linkedInEnabled: this.linkedInConnected,  // Auto-enable if connected
    };
  }

  editJob(job: Job): void {
    this.showForm = true;
    this.editingJobId = job.id;
    this.form = {
      title: job.title,
      department: job.department || '',
      location: job.location || '',
      employmentType: job.employmentType || 'Full-time',
      salaryMin: job.salaryMin?.toString() || '',
      salaryMax: job.salaryMax?.toString() || '',
      description: job.description,
      requirements: job.requirements || '',
      niceToHave: job.niceToHave || '',
      atsKeywords: job.atsKeywords?.join(', ') || '',
      linkedInEnabled: job.linkedInEnabled || false,
    };
  }

  cancelForm(): void {
    this.showForm = false;
    this.editingJobId = null;
    this.form = {
      title: '',
      department: '',
      location: '',
      employmentType: 'Full-time',
      salaryMin: '',
      salaryMax: '',
      description: '',
      requirements: '',
      niceToHave: '',
      atsKeywords: '',
      linkedInEnabled: false,
    };
  }

  saveDraft(): void {
    this.upsertJob(JobStatus.DRAFT);
  }

  publishNow(): void {
    this.upsertJob(JobStatus.OPEN);
  }

  changeJobStatus(job: Job, status: JobStatus): void {
    const payload: CreateJobRequest = {
      title: job.title,
      department: job.department,
      description: job.description,
      status,
    };

    this.saving = true;
    this.jobApi.updateJob(job.id, payload).subscribe({
      next: () => {
        this.saving = false;
        this.loadData();
      },
      error: (err) => {
        this.error = err?.message ?? 'Unable to update requisition status.';
        this.saving = false;
      },
    });
  }

  get openCount(): number {
    return this.jobs.filter((job) => job.status === JobStatus.OPEN).length;
  }

  get totalApplicants(): number {
    return this.jobs.reduce((total, job) => total + this.applicantCount(job.id), 0);
  }

  get avgDaysOpen(): number {
    if (!this.jobs.length) {
      return 0;
    }

    const totalDays = this.jobs.reduce((total, job) => total + this.daysOpen(job.createdAt), 0);
    return Math.round(totalDays / this.jobs.length);
  }

  get totalPages(): number {
    return Math.ceil(this.jobs.length / this.pageSize);
  }

  get paginatedJobs(): Job[] {
    const start = (this.currentPage - 1) * this.pageSize;
    const end = start + this.pageSize;
    return this.jobs.slice(start, end);
  }

  nextPage(): void {
    if (this.currentPage < this.totalPages) {
      this.currentPage++;
    }
  }

  prevPage(): void {
    if (this.currentPage > 1) {
      this.currentPage--;
    }
  }

  goToPage(page: number): void {
    if (page >= 1 && page <= this.totalPages) {
      this.currentPage = page;
    }
  }

  applicantCount(jobId: number): number {
    return this.applications.filter((application) => application.jobId === jobId).length;
  }

  interviewCount(jobId: number): number {
    const interviewLikeStatuses = new Set<ApplicationStatus>([
      ApplicationStatus.INTERVIEW_SCHEDULED,
      ApplicationStatus.INTERVIEWED,
      ApplicationStatus.OFFER_EXTENDED,
      ApplicationStatus.HIRED,
    ]);

    return this.applications.filter(
      (application) =>
        application.jobId === jobId && interviewLikeStatuses.has(application.status as ApplicationStatus)
    ).length;
  }

  daysOpen(createdAt: string): number {
    const created = new Date(createdAt).getTime();
    const now = Date.now();
    const diff = now - created;
    return Math.max(1, Math.floor(diff / (1000 * 60 * 60 * 24)));
  }

  interviewConversion(jobId: number): number {
    const applicants = this.applicantCount(jobId);
    if (!applicants) {
      return 0;
    }

    return Math.round((this.interviewCount(jobId) / applicants) * 100);
  }

  private upsertJob(status: JobStatus): void {
    if (!this.form.title.trim() || !this.form.department.trim() || !this.form.description.trim()) {
      this.error = 'Title, department, and description are required.';
      return;
    }

    const keywords = this.form.atsKeywords
      .split(',')
      .map((k) => k.trim())
      .filter(Boolean);

    const payload: CreateJobRequest = {
      title: this.form.title.trim(),
      department: this.form.department.trim(),
      location: this.form.location.trim() || undefined,
      employmentType: this.form.employmentType || undefined,
      salaryMin: this.form.salaryMin ? parseInt(this.form.salaryMin, 10) : undefined,
      salaryMax: this.form.salaryMax ? parseInt(this.form.salaryMax, 10) : undefined,
      description: this.form.description.trim(),
      requirements: this.form.requirements.trim() || undefined,
      niceToHave: this.form.niceToHave.trim() || undefined,
      atsKeywords: keywords.length > 0 ? keywords : undefined,
      linkedInEnabled: this.form.linkedInEnabled,
      status,
    };

    this.saving = true;
    this.error = '';

    const request$ = this.editingJobId
      ? this.jobApi.updateJob(this.editingJobId, payload)
      : this.jobApi.createJob(payload);

    request$.subscribe({
      next: () => {
        this.saving = false;
        this.cancelForm();
        this.loadData();
      },
      error: (err) => {
        this.error = err?.message ?? 'Unable to save requisition.';
        this.saving = false;
      },
    });
  }

  connectLinkedIn(): void {
    // TODO: Implement OAuth LinkedIn connection flow
    // For now, simulate connection
    alert('LinkedIn OAuth flow would start here. In production:\n\n1. Redirect to LinkedIn OAuth\n2. User authorizes company page access\n3. Store access token\n4. Enable job posting');

    // Simulate successful connection
    this.linkedInConnected = true;
    this.form.linkedInEnabled = true;
  }
}
