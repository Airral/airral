import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApplicationApiService, JobApiService } from '@airral/shared-api';
import { Application, ApplicationStatus } from '@airral/shared-types';
import { OrganizationService } from '@airral/shared-utils';
import { combineLatest, finalize } from 'rxjs';

@Component({
  selector: 'app-quick-hire-home',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="hiring-home">
      <header class="home-header">
        <div>
          <p class="eyebrow">Hiring overview</p>
          <h1>{{ organizationName }}</h1>
          <p>Keep the team focused on candidates who need a decision today.</p>
        </div>
        <a routerLink="/jobs" class="primary-action">
          <span class="material-icons" aria-hidden="true">add</span>Post a job
        </a>
      </header>

      <section class="summary" aria-label="Hiring summary">
        <a routerLink="/jobs"><span>Open jobs</span><strong>{{ openJobs }}</strong><small>Manage roles</small></a>
        <a routerLink="/candidates" [queryParams]="{ stage: 'SUBMITTED' }"><span>New applications</span><strong>{{ newApplications }}</strong><small>Start review</small></a>
        <a routerLink="/interviews"><span>Interviews</span><strong>{{ interviews }}</strong><small>View schedule</small></a>
        <a routerLink="/offers"><span>Open offers</span><strong>{{ offers }}</strong><small>Review offers</small></a>
      </section>

      @if (error) {
        <div class="error" role="alert"><span class="material-icons">error_outline</span>{{ error }}</div>
      }

      <div class="home-grid">
        <section class="work-section">
          <div class="section-header">
            <div><p class="eyebrow">Priority queue</p><h2>Candidates needing attention</h2></div>
            <a routerLink="/candidates">View all<span class="material-icons">arrow_forward</span></a>
          </div>

          @if (loading) {
            <div class="empty">Loading hiring activity...</div>
          } @else {
            <div class="candidate-table">
              @for (application of priorityApplications; track application.id) {
                <a routerLink="/candidates" class="candidate-line">
                  <span class="avatar">{{ initials(application) }}</span>
                  <span class="candidate-name"><strong>{{ application.applicantName || application.applicantEmail }}</strong><small>{{ application.jobTitle || 'Open role' }}</small></span>
                  <span class="stage" [attr.data-stage]="application.status">{{ stageLabel(application.status) }}</span>
                  <time>{{ application.appliedAt | date: 'MMM d' }}</time>
                  <span class="material-icons arrow">chevron_right</span>
                </a>
              } @empty {
                <div class="empty">
                  <span class="material-icons">task_alt</span>
                  <strong>No candidates need attention</strong>
                  <p>New applications and interview decisions will appear here.</p>
                </div>
              }
            </div>
          }
        </section>

        <aside class="next-actions">
          <div class="section-header"><div><p class="eyebrow">Workflow</p><h2>Next actions</h2></div></div>
          <a routerLink="/candidates">
            <span class="action-icon material-icons">person_search</span>
            <span><strong>Review candidates</strong><small>{{ newApplications }} waiting to be reviewed</small></span>
            <span class="material-icons">chevron_right</span>
          </a>
          <a routerLink="/interviews">
            <span class="action-icon material-icons">event</span>
            <span><strong>Coordinate interviews</strong><small>{{ interviews }} currently in progress</small></span>
            <span class="material-icons">chevron_right</span>
          </a>
          <a routerLink="/jobs">
            <span class="action-icon material-icons">work_outline</span>
            <span><strong>Manage jobs</strong><small>{{ openJobs }} roles accepting applications</small></span>
            <span class="material-icons">chevron_right</span>
          </a>
        </aside>
      </div>
    </main>
  `,
  styles: [`
    :host { display: block; color: #18201f; }
    * { box-sizing: border-box; }
    .hiring-home { max-width: 1380px; margin: 0 auto; padding: 28px 32px 48px; }
    .home-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 22px; }
    .eyebrow { margin: 0 0 5px; color: #087f70; font-size: 11px; font-weight: 800; text-transform: uppercase; }
    h1 { margin: 0; font: 700 28px/1.2 'Sora', sans-serif; letter-spacing: 0; }
    .home-header p:not(.eyebrow) { margin: 7px 0 0; color: #697371; font-size: 14px; }
    .primary-action { min-height: 38px; display: inline-flex; align-items: center; gap: 7px; padding: 0 14px; border: 1px solid #087f70; border-radius: 6px; color: #fff; background: #087f70; font-size: 12px; font-weight: 800; text-decoration: none; }
    .primary-action .material-icons { font-size: 18px; }
    .summary { display: grid; grid-template-columns: repeat(4, 1fr); margin-bottom: 20px; border: 1px solid #dfe4e3; border-radius: 7px; background: #fff; }
    .summary a { display: grid; grid-template-columns: 1fr auto; gap: 4px 12px; padding: 15px 18px; border-right: 1px solid #e6eae9; color: inherit; text-decoration: none; }
    .summary a:last-child { border-right: 0; }
    .summary span { color: #697371; font-size: 12px; }.summary strong { grid-row: span 2; font: 700 23px/1.25 'Sora', sans-serif; }
    .summary small { color: #087f70; font-size: 10px; }
    .summary a:hover { background: #f7faf9; }
    .error { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; padding: 10px 12px; border: 1px solid #efc7c3; border-radius: 6px; color: #9d2c24; background: #fff7f6; font-size: 12px; }
    .error .material-icons { font-size: 18px; }
    .home-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .7fr); gap: 18px; }
    .work-section, .next-actions { border: 1px solid #dfe4e3; border-radius: 8px; background: #fff; }
    .section-header { min-height: 66px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 16px; border-bottom: 1px solid #e8eceb; }
    .section-header h2 { margin: 0; font: 700 15px/1.3 'Sora', sans-serif; letter-spacing: 0; }
    .section-header a { display: inline-flex; align-items: center; gap: 4px; color: #087f70; font-size: 11px; font-weight: 800; text-decoration: none; }
    .section-header a .material-icons { font-size: 15px; }
    .candidate-line { min-height: 69px; display: grid; grid-template-columns: 36px minmax(0, 1fr) auto 50px 20px; align-items: center; gap: 11px; padding: 10px 14px; border-bottom: 1px solid #edf0ef; color: inherit; text-decoration: none; }
    .candidate-line:last-child { border-bottom: 0; }.candidate-line:hover { background: #f8faf9; }
    .avatar { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid #d1d9d7; border-radius: 50%; color: #44504e; background: #f2f5f4; font-size: 11px; font-weight: 800; }
    .candidate-name { min-width: 0; display: flex; flex-direction: column; gap: 3px; }.candidate-name strong, .candidate-name small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .candidate-name strong { font-size: 12px; }.candidate-name small { color: #6e7876; font-size: 11px; }
    .stage { padding: 4px 7px; border: 1px solid #d5dcda; border-radius: 5px; color: #596461; background: #f7f9f8; font-size: 10px; font-weight: 800; }
    .stage[data-stage='SUBMITTED'], .stage[data-stage='UNDER_REVIEW'] { border-color: #b8d8e5; color: #28647d; background: #f0f8fb; }
    .stage[data-stage='INTERVIEW_SCHEDULED'], .stage[data-stage='INTERVIEWED'] { border-color: #e5d49b; color: #785f12; background: #fffaf0; }
    .candidate-line time { color: #838c8a; font-size: 10px; }.candidate-line .arrow { color: #9ba3a1; font-size: 18px; }
    .next-actions > a { min-height: 72px; display: grid; grid-template-columns: 34px 1fr 18px; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid #edf0ef; color: inherit; text-decoration: none; }
    .next-actions > a:last-child { border-bottom: 0; }.next-actions > a:hover { background: #f8faf9; }
    .next-actions .action-icon { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 6px; color: #087f70; background: #eaf6f3; font-size: 18px; }
    .next-actions a > span:nth-child(2) { min-width: 0; display: flex; flex-direction: column; gap: 3px; }.next-actions strong { font-size: 12px; }.next-actions small { overflow: hidden; color: #737d7a; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
    .next-actions a > .material-icons:last-child { color: #9ba3a1; font-size: 18px; }
    .empty { min-height: 260px; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 30px; color: #7d8684; font-size: 12px; text-align: center; }
    .empty .material-icons { margin-bottom: 8px; font-size: 28px; }.empty strong { color: #3e4846; }.empty p { margin: 5px 0 0; }
    @media (max-width: 900px) { .home-grid { grid-template-columns: 1fr; }.summary a { padding: 13px; } }
    @media (max-width: 700px) {
      .hiring-home { padding: 18px 12px 32px; }.home-header { align-items: center; } h1 { font-size: 23px; }
      .summary { grid-template-columns: 1fr 1fr; }.summary a:nth-child(2) { border-right: 0; }.summary a:nth-child(-n+2) { border-bottom: 1px solid #e6eae9; }
      .candidate-line { grid-template-columns: 36px minmax(0, 1fr) auto 18px; }.candidate-line time { display: none; }.stage { max-width: 88px; overflow: hidden; text-overflow: ellipsis; }
    }
  `],
})
export class QuickHireHomeComponent implements OnInit {
  private readonly jobApi = inject(JobApiService);
  private readonly applicationApi = inject(ApplicationApiService);
  private readonly orgService = inject(OrganizationService);

  applications: Application[] = [];
  openJobs = 0;
  newApplications = 0;
  interviews = 0;
  offers = 0;
  loading = true;
  error = '';

  get organizationName(): string {
    return this.orgService.organization.name;
  }

  get priorityApplications(): Application[] {
    const priority: ApplicationStatus[] = [
      ApplicationStatus.SUBMITTED,
      ApplicationStatus.UNDER_REVIEW,
      ApplicationStatus.INTERVIEW_SCHEDULED,
      ApplicationStatus.INTERVIEWED,
      ApplicationStatus.OFFER_EXTENDED,
    ];
    return this.applications.filter((application) => priority.includes(application.status)).slice(0, 7);
  }

  ngOnInit(): void {
    combineLatest({
      jobs: this.jobApi.getAllJobs(),
      applications: this.applicationApi.getAllApplications(),
    })
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: ({ jobs, applications }) => {
          this.openJobs = jobs.filter((job) => job.status === 'OPEN').length;
          this.applications = [...applications].sort(
            (a, b) => new Date(b.updatedAt || b.appliedAt).getTime() - new Date(a.updatedAt || a.appliedAt).getTime(),
          );
          this.newApplications = applications.filter((item) => item.status === ApplicationStatus.SUBMITTED).length;
          this.interviews = applications.filter((item) =>
            item.status === ApplicationStatus.INTERVIEW_SCHEDULED || item.status === ApplicationStatus.INTERVIEWED,
          ).length;
          this.offers = applications.filter((item) => item.status === ApplicationStatus.OFFER_EXTENDED).length;
        },
        error: (error: Error) => {
          this.error = error.message || 'Unable to load the hiring overview.';
        },
      });
  }

  initials(application: Application): string {
    return (application.applicantName || application.applicantEmail)
      .split(/[\s@._-]+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0].toUpperCase())
      .join('');
  }

  stageLabel(status: ApplicationStatus): string {
    return {
      SUBMITTED: 'New',
      UNDER_REVIEW: 'In review',
      SHORTLISTED: 'Shortlisted',
      INTERVIEW_SCHEDULED: 'Interview',
      INTERVIEWED: 'Decision',
      OFFER_EXTENDED: 'Offer',
      HIRED: 'Hired',
      REJECTED: 'Rejected',
      WITHDRAWN: 'Withdrawn',
    }[status];
  }
}
