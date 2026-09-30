import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  CompanyApiService,
  Department,
  DepartmentApiService,
  InterviewKitApiService,
  Invitation,
  JobApiService,
  UserApiService,
} from '@airral/shared-api';
import { CompanyProfile, InterviewKit, Job, User } from '@airral/shared-types';
import { catchError, forkJoin, of } from 'rxjs';

interface SetupStep {
  key: string;
  title: string;
  detail: string;
  done: boolean;
  optional?: boolean;
  route?: string;
  action?: string;
}

/**
 * A new company's first steps, each ticked off from what the company has
 * actually done. It hides once the required steps are done, or when HR hides
 * it.
 */
@Component({
  selector: 'app-setup-checklist',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    @if (visible) {
      <section class="setup" aria-labelledby="setup-heading">
        <header>
          <div>
            <p class="eyebrow">Get set up</p>
            <h2 id="setup-heading">{{ doneCount }} of {{ requiredCount }} steps done</h2>
          </div>
          <button type="button" class="hide" (click)="hide()">Hide</button>
        </header>
        <div class="progress" aria-hidden="true"><span [style.width.%]="(doneCount / requiredCount) * 100"></span></div>
        <ol>
          @for (step of steps; track step.key) {
            <li [class.done]="step.done">
              <span class="mark material-icons" aria-hidden="true">{{ step.done ? 'check_circle' : 'radio_button_unchecked' }}</span>
              <span class="text">
                <strong>{{ step.title }} @if (step.optional) { <em>Optional</em> }</strong>
                <small>{{ step.detail }}</small>
              </span>
              @if (step.route && !step.done) {
                <a [routerLink]="step.route" class="go">{{ step.action || 'Open' }}</a>
              } @else if (step.done) {
                <span class="sr-only">Done</span>
              }
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: [`
    :host { display: block; }
    .setup { margin-bottom: 20px; padding: 16px 18px; border: 1px solid #b9ded7; border-radius: 8px; background: #fff; }
    header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
    .eyebrow { margin: 0 0 4px; color: #087f70; font-size: 11px; font-weight: 800; text-transform: uppercase; }
    h2 { margin: 0; font: 700 16px/1.3 'Sora', sans-serif; letter-spacing: 0; }
    .hide { border: 0; background: none; color: #697371; font-size: 12px; font-weight: 700; cursor: pointer; }
    .hide:hover { color: #087f70; }
    .progress { height: 6px; margin: 12px 0 8px; border-radius: 999px; background: #edf0ef; overflow: hidden; }
    .progress span { display: block; height: 100%; background: #087f70; }
    ol { margin: 0; padding: 0; list-style: none; }
    li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 10px 0; border-top: 1px solid #edf0ef; }
    li:first-child { border-top: 0; }
    .mark { color: #a2aaa8; font-size: 20px; }
    li.done .mark { color: #087f70; }
    .text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .text strong { font-size: 13px; }
    li.done .text strong { color: #5f6967; }
    .text em { margin-left: 6px; color: #8a9290; font-size: 11px; font-style: normal; font-weight: 600; }
    .text small { color: #6e7876; font-size: 12px; }
    .go { padding: 6px 11px; border: 1px solid #087f70; border-radius: 6px; color: #087f70; font-size: 12px; font-weight: 800; text-decoration: none; white-space: nowrap; }
    .go:hover { color: #fff; background: #087f70; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
  `],
})
export class SetupChecklistComponent implements OnInit {
  private readonly companyApi = inject(CompanyApiService);
  private readonly userApi = inject(UserApiService);
  private readonly jobApi = inject(JobApiService);
  private readonly kitApi = inject(InterviewKitApiService);
  private readonly departmentApi = inject(DepartmentApiService);

  steps: SetupStep[] = [];
  private loaded = false;
  private hidden = false;
  private companyId: number | null = null;

  get requiredCount(): number {
    return this.steps.filter((step) => !step.optional).length;
  }

  get doneCount(): number {
    return this.steps.filter((step) => !step.optional && step.done).length;
  }

  get visible(): boolean {
    return this.loaded && !this.hidden && this.doneCount < this.requiredCount;
  }

  ngOnInit(): void {
    forkJoin({
      company: this.companyApi.get().pipe(catchError(() => of(null as CompanyProfile | null))),
      people: this.userApi.getAllUsers().pipe(catchError(() => of([] as User[]))),
      invitations: this.userApi.getPendingInvitations().pipe(catchError(() => of([] as Invitation[]))),
      jobs: this.jobApi.getAllJobs().pipe(catchError(() => of([] as Job[]))),
      kits: this.kitApi.list().pipe(catchError(() => of([] as InterviewKit[]))),
      departments: this.departmentApi.list().pipe(catchError(() => of([] as Department[]))),
    }).subscribe(({ company, people, invitations, jobs, kits, departments }) => {
      this.companyId = company?.id ?? null;
      this.hidden = this.readHidden();
      this.steps = [
        {
          key: 'profile',
          title: 'Tell candidates about your company',
          detail: 'Your industry, size and website or a few lines about you. They show on every job.',
          done: !!company && !!company.industry && !!company.companySizeRange && !!(company.website || company.about),
          route: '/settings/company',
          action: 'Add profile',
        },
        {
          key: 'team',
          title: 'Invite the people who hire with you',
          detail: "Hiring managers see their jobs' candidates, and interviewers score interviews.",
          done: people.filter((person) => person.isActive !== false).length > 1 || invitations.length > 0,
          route: '/settings/team',
          action: 'Invite',
        },
        {
          key: 'job',
          title: 'Post your first job',
          detail: 'Published jobs go on apply.airral.com once AIRRAL has verified your company.',
          done: jobs.some((job) => job.status !== 'DRAFT'),
          route: '/jobs',
          action: 'Post a job',
        },
        {
          key: 'verified',
          title: 'AIRRAL verifies your company',
          detail: company?.verificationStatus === 'REJECTED'
            ? 'We could not verify your company. Email contact@airral.com and we will look again.'
            : 'We review every new company, usually within one business day. Nothing to do here.',
          done: company?.verificationStatus === 'VERIFIED',
        },
        {
          key: 'kit',
          title: 'Set up an interview kit',
          detail: 'The questions to ask and what to rate. Jobs without one use the standard criteria.',
          done: kits.length > 0,
          optional: true,
          route: '/settings/interview-kits',
        },
        {
          key: 'departments',
          title: 'Add your departments',
          detail: 'File jobs and people under the teams you have.',
          // Signup makes Human Resources; the company adding its own is the step.
          done: departments.length > 1,
          optional: true,
          route: '/settings/departments',
        },
      ];
      this.loaded = true;
    });
  }

  hide(): void {
    this.hidden = true;
    try {
      localStorage.setItem(this.storageKey(), '1');
    } catch {
      // Private windows can refuse storage; it hides for this visit.
    }
  }

  private readHidden(): boolean {
    try {
      return localStorage.getItem(this.storageKey()) === '1';
    } catch {
      return false;
    }
  }

  private storageKey(): string {
    return `airral.setupChecklist.hidden.${this.companyId ?? 'unknown'}`;
  }
}
