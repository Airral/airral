import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApplicationApiService, JobApiService } from '@airral/shared-api';
import { Application, ApplicationStatus } from '@airral/shared-types';
import { OrganizationService } from '@airral/shared-utils';
import { combineLatest, finalize } from 'rxjs';
import { SetupChecklistComponent } from './setup-checklist.component';

@Component({
  selector: 'app-quick-hire-home',
  standalone: true,
  imports: [CommonModule, RouterLink, SetupChecklistComponent],
  template: `
    <main class="hiring-home">
      <header class="home-header">
        <div>
          <p class="date">{{ today | date: 'EEEE, MMMM d' }}</p>
          <h1>{{ headline }}</h1>
          <p class="sub">{{ organizationName }} · {{ subline }}</p>
        </div>
        <a routerLink="/jobs" class="btn btn-primary">
          <span class="material-icons" aria-hidden="true">add</span>Post a job
        </a>
      </header>

      <app-setup-checklist></app-setup-checklist>

      <!-- Four numbers at a glance, each colored like its stage everywhere else. -->
      <section class="glance" aria-label="Hiring summary">
        <a routerLink="/candidates" [queryParams]="{ stage: 'SUBMITTED' }" class="tile t-blue">
          <span class="tile-label">New applications</span><strong>{{ newApplications }}</strong><small>Start review</small>
        </a>
        <a routerLink="/interviews" class="tile t-magenta">
          <span class="tile-label">Interviews</span><strong>{{ interviews }}</strong><small>View schedule</small>
        </a>
        <a routerLink="/offers" class="tile t-green">
          <span class="tile-label">Open offers</span><strong>{{ offers }}</strong><small>Review offers</small>
        </a>
        <a routerLink="/jobs" class="tile t-teal">
          <span class="tile-label">Open jobs</span><strong>{{ openJobs }}</strong><small>Manage jobs</small>
        </a>
      </section>

      @if (error) {
        <div class="error" role="alert"><span class="material-icons" aria-hidden="true">error_outline</span>{{ error }}</div>
      }

      <div class="home-grid">
        <section class="panel">
          <div class="panel-head">
            <h2>Needs your attention</h2>
            <a routerLink="/candidates" class="link">View all<span class="material-icons" aria-hidden="true">chevron_right</span></a>
          </div>

          @if (loading) {
            <div class="empty">Loading hiring activity…</div>
          } @else {
            <div class="list">
              @for (application of priorityApplications; track application.id) {
                <a routerLink="/candidates" [queryParams]="{ stage: application.status }" class="row">
                  <span class="avatar">{{ initials(application) }}</span>
                  <span class="row-text">
                    <strong>{{ application.applicantName || application.applicantEmail }}</strong>
                    <small>{{ application.jobTitle || 'Open role' }} · applied {{ application.appliedAt | date: 'MMM d' }}</small>
                  </span>
                  <span class="stage" [attr.data-stage]="application.status">{{ stageLabel(application.status) }}</span>
                  <span class="material-icons chevron" aria-hidden="true">chevron_right</span>
                </a>
              } @empty {
                <div class="empty">
                  <span class="empty-icon material-icons" aria-hidden="true">task_alt</span>
                  <strong>Nothing is waiting on you</strong>
                  <p>New applications and interview decisions show up here.</p>
                </div>
              }
            </div>
          }
        </section>

        <aside class="panel">
          <div class="panel-head"><h2>Next actions</h2></div>
          <div class="list">
            <a routerLink="/candidates" class="row action">
              <span class="icon-tile c-blue material-icons" aria-hidden="true">person_search</span>
              <span class="row-text"><strong>Review candidates</strong><small>{{ newApplications }} waiting to be reviewed</small></span>
              <span class="material-icons chevron" aria-hidden="true">chevron_right</span>
            </a>
            <a routerLink="/interviews" class="row action">
              <span class="icon-tile c-magenta material-icons" aria-hidden="true">event</span>
              <span class="row-text"><strong>Coordinate interviews</strong><small>{{ interviews }} in progress</small></span>
              <span class="material-icons chevron" aria-hidden="true">chevron_right</span>
            </a>
            <a routerLink="/jobs" class="row action">
              <span class="icon-tile c-teal material-icons" aria-hidden="true">work_outline</span>
              <span class="row-text"><strong>Manage jobs</strong><small>{{ openJobs }} taking applications</small></span>
              <span class="material-icons chevron" aria-hidden="true">chevron_right</span>
            </a>
          </div>
        </aside>
      </div>
    </main>
  `,
  styles: [`
    :host { display: block; color: var(--ap-ink); }
    * { box-sizing: border-box; }
    .hiring-home { max-width: 1240px; margin: 0 auto; padding: 28px 32px 48px; display: grid; gap: 20px; }
    .home-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
    .date { margin: 0; color: var(--ap-ink-3); font-size: 12px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }
    h1 { margin: 4px 0 0; font: 800 30px/1.15 'Sora', 'Manrope', sans-serif; letter-spacing: -.02em; }
    .sub { margin: 6px 0 0; color: var(--ap-ink-2); font-size: 14px; }
    .btn { display: inline-flex; align-items: center; justify-content: center; gap: 7px; height: 40px; padding: 0 16px; border-radius: 12px; font-size: 14px; font-weight: 700; text-decoration: none; white-space: nowrap; }
    .btn-primary { background: var(--ap-tint); color: #fff; }
    .btn-primary:hover { background: var(--ap-tint-hover); }
    .btn .material-icons { font-size: 18px; }
    .glance { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
    .tile { --c: var(--ap-teal); display: grid; gap: 2px; padding: 14px 16px; border-radius: 16px; background: var(--ap-surface); color: inherit; text-decoration: none; box-shadow: 0 1px 2px rgba(16,24,40,.04); transition: box-shadow .15s; }
    .tile:hover { box-shadow: 0 1px 2px rgba(16,24,40,.05), 0 8px 24px rgba(16,24,40,.08); }
    .tile-label { color: var(--ap-ink-2); font-size: 12.5px; font-weight: 650; }
    .tile strong { color: var(--c); font: 800 32px/1.1 'Sora', 'Manrope', sans-serif; letter-spacing: -.02em; font-variant-numeric: tabular-nums; }
    .tile small { color: var(--ap-tint-text); font-size: 12px; font-weight: 700; }
    .t-blue { --c: var(--ap-blue); } .t-magenta { --c: var(--ap-magenta); } .t-green { --c: var(--ap-green); } .t-teal { --c: var(--ap-teal); }
    .error { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 12px; color: var(--ap-red); background: color-mix(in srgb, var(--ap-red) 9%, #fff); font-size: 13px; font-weight: 650; }
    .error .material-icons { font-size: 18px; }
    .home-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(280px, .75fr); gap: 16px; align-items: start; }
    .panel { border-radius: 16px; background: var(--ap-surface); box-shadow: 0 1px 2px rgba(16,24,40,.04); overflow: hidden; }
    .panel-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px 8px; }
    .panel-head h2 { margin: 0; font-size: 16px; letter-spacing: -.01em; }
    .link { display: inline-flex; align-items: center; color: var(--ap-tint-text); font-size: 13px; font-weight: 700; text-decoration: none; }
    .link .material-icons { font-size: 18px; }
    .list { padding: 0 6px 8px; }
    .row { position: relative; display: grid; grid-template-columns: 36px minmax(0, 1fr) auto 18px; align-items: center; gap: 12px; padding: 11px 10px; border-radius: 12px; color: inherit; text-decoration: none; }
    .row + .row::before { content: ""; position: absolute; left: 58px; right: 10px; top: 0; border-top: 1px solid var(--ap-hair); }
    .row:hover { background: var(--ap-fill-soft); }
    .row.action { grid-template-columns: 34px minmax(0, 1fr) 18px; }
    .avatar { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 50%; color: var(--ap-ink); background: var(--ap-fill); font-size: 12px; font-weight: 800; }
    .row-text { min-width: 0; display: grid; gap: 2px; }
    .row-text strong, .row-text small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .row-text strong { font-size: 14px; }
    .row-text small { color: var(--ap-ink-2); font-size: 12.5px; }
    .chevron { color: var(--ap-ink-3); font-size: 18px; }
    .icon-tile { width: 34px; height: 34px; display: grid; place-items: center; border-radius: 10px; color: #fff; background: var(--c); font-size: 19px; }
    .c-blue { --c: var(--ap-blue); } .c-magenta { --c: var(--ap-magenta); } .c-teal { --c: var(--ap-teal); }
    .empty { min-height: 220px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; padding: 28px; color: var(--ap-ink-2); font-size: 13px; text-align: center; }
    .empty-icon { width: 40px; height: 40px; display: grid; place-items: center; margin-bottom: 6px; border-radius: 12px; color: #fff; background: var(--ap-green); font-size: 22px; }
    .empty strong { color: var(--ap-ink); font-size: 15px; }
    .empty p { margin: 0; }
    :is(.btn, .tile, .row, .link):focus-visible { outline: 3px solid var(--ap-tint); outline-offset: 2px; }
    @media (max-width: 1000px) { .home-grid { grid-template-columns: 1fr; } .glance { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 640px) {
      .hiring-home { padding: 18px 12px 32px; gap: 16px; }
      .home-header { flex-direction: column; align-items: stretch; gap: 14px; }
      h1 { font-size: 25px; }
      .tile strong { font-size: 26px; }
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

  readonly today = new Date();

  get organizationName(): string {
    return this.orgService.organization.name;
  }

  /** The page's one-line answer to "what do I do now?". */
  get headline(): string {
    if (this.loading) return 'Hiring overview';
    const waiting = this.priorityApplications.length;
    if (this.newApplications > 0) {
      return `${this.newApplications} new ${this.newApplications === 1 ? 'person' : 'people'} to review`;
    }
    if (waiting > 0) return `${waiting} ${waiting === 1 ? 'candidate needs' : 'candidates need'} a decision`;
    return this.openJobs > 0 ? 'Nothing is waiting on you' : 'Post a job to start hiring';
  }

  get subline(): string {
    return `${this.openJobs} open ${this.openJobs === 1 ? 'job' : 'jobs'} · ${this.interviews} in interviews`;
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
