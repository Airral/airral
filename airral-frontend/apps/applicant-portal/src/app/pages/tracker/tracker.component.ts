import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { ApplicationApiService, CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { ApplicantStage, CandidateSavedJob, MyApplication, Offer } from '@airral/shared-types';
import { catchError, finalize, forkJoin, of, timeout } from 'rxjs';
import { CompanyLogoComponent } from '../../components/company-logo.component';

type TrackerStatus = 'SAVED' | 'APPLYING' | 'APPLIED' | 'INTERVIEWING' | 'OFFER' | 'REJECTED' | 'ARCHIVED';

/**
 * One card on the board: a job the applicant saved and moves by hand, or an
 * application on AIRRAL, which the company moves.
 */
interface TrackerCard {
  key: string;
  company: string;
  title: string;
  note?: string;
  saved?: CandidateSavedJob;
  application?: MyApplication;
  /** The column the card sits in, whichever of the two sources it came from. */
  status: TrackerStatus;
}

/** A job the applicant applied to a week ago and has not heard back on is due a follow-up. */
const FOLLOW_UP_AFTER_DAYS = 7;
const DAY_MS = 24 * 60 * 60 * 1000;
const VIEW_KEY = 'airral.applications.view';

interface TrackerColumn {
  status: TrackerStatus;
  label: string;
  icon: string;
  cards: TrackerCard[];
}

/** The column each applicant stage sits in. */
const COLUMN_FOR_STAGE: Record<ApplicantStage, TrackerStatus> = {
  APPLIED: 'APPLIED',
  IN_REVIEW: 'APPLIED',
  INTERVIEWING: 'INTERVIEWING',
  OFFER: 'OFFER',
  HIRED: 'OFFER',
  NOT_SELECTED: 'REJECTED',
  WITHDRAWN: 'REJECTED',
};

const STAGE_LABELS: Record<ApplicantStage, string> = {
  APPLIED: 'Applied',
  IN_REVIEW: 'In review',
  INTERVIEWING: 'Interviewing',
  OFFER: 'Offer',
  HIRED: 'Hired',
  NOT_SELECTED: 'Not moving forward',
  WITHDRAWN: 'Withdrawn',
};

/**
 * The AIRRAL job a saved job is, when it is one a company posted here. The
 * catalogue's summary says so while the job is listed; once it is filled or
 * closed the summary is gone, and the saved job's key still names it:
 * airral_internal:organization-<company>:<job id>.
 */
function readView(): 'todo' | 'board' {
  try {
    return localStorage.getItem(VIEW_KEY) === 'board' ? 'board' : 'todo';
  } catch {
    return 'todo';
  }
}

function internalJobIdOf(saved: CandidateSavedJob): number | null {
  if (saved.job?.sourceType === 'AIRRAL_INTERNAL' && saved.job.externalJobId) {
    const id = Number(saved.job.externalJobId);
    return Number.isInteger(id) ? id : null;
  }
  const match = /^airral_internal:organization-\d+:(\d+)$/.exec(saved.sourceJobKey ?? '');
  return match ? Number(match[1]) : null;
}

@Component({
  selector: 'app-tracker',
  standalone: true,
  imports: [CommonModule, RouterModule, CompanyLogoComponent],
  templateUrl: './tracker.component.html',
  styleUrl: './tracker.component.css',
})
export class TrackerComponent implements OnInit {
  private readonly trackerTimeoutMs = 12000;

  columns: TrackerColumn[] = [
    { status: 'SAVED', label: 'Saved', icon: 'bookmark', cards: [] },
    { status: 'APPLYING', label: 'Applying', icon: 'edit_note', cards: [] },
    { status: 'APPLIED', label: 'Applied', icon: 'send', cards: [] },
    { status: 'INTERVIEWING', label: 'Interviewing', icon: 'groups', cards: [] },
    { status: 'OFFER', label: 'Offer', icon: 'celebration', cards: [] },
    { status: 'REJECTED', label: 'Rejected', icon: 'block', cards: [] },
  ];

  loading = false;
  totalJobs = 0;
  errorMessage = '';

  /** Every card, for the to-do view, which groups by what needs doing rather than by column. */
  cards: TrackerCard[] = [];
  view: 'todo' | 'board' = readView();
  expanded = new Set<string>();
  showClosed = false;
  notice = '';
  noticeError = false;

  readonly statusOptions: { value: TrackerStatus; label: string }[] = [
    { value: 'SAVED', label: 'Saved' },
    { value: 'APPLYING', label: 'Applying' },
    { value: 'APPLIED', label: 'Applied' },
    { value: 'INTERVIEWING', label: 'Interviewing' },
    { value: 'OFFER', label: 'Offer' },
    { value: 'REJECTED', label: 'Not selected' },
    { value: 'ARCHIVED', label: 'Archived' },
  ];

  /** Offers companies sent through AIRRAL, open ones first. */
  offers: Offer[] = [];
  confirming: { offerId: number; accept: boolean } | null = null;
  answering: number | null = null;
  offerMessage = '';
  offerError = '';

  constructor(
    private readonly candidateApi: CandidatePortalService,
    private readonly applicationApi: ApplicationApiService,
    private readonly auth: AuthService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadSavedJobs();
  }

  /** Saved jobs and AIRRAL applications together. Either one failing leaves the other on the board. */
  loadSavedJobs(): void {
    this.loading = true;
    this.errorMessage = '';
    let failed = false;
    let offersFailed = false;
    const userId = this.auth.getCurrentUser()?.id;

    forkJoin({
      saved: this.candidateApi.getSavedJobs().pipe(
        timeout(this.trackerTimeoutMs),
        catchError(() => {
          failed = true;
          return of([] as CandidateSavedJob[]);
        })
      ),
      applications: (userId ? this.applicationApi.getMyApplications(userId) : of([] as MyApplication[])).pipe(
        timeout(this.trackerTimeoutMs),
        catchError(() => {
          failed = true;
          return of([] as MyApplication[]);
        })
      ),
      offers: (userId ? this.applicationApi.getMyOffers() : of([] as Offer[])).pipe(
        timeout(this.trackerTimeoutMs),
        catchError(() => {
          // Not silent: an applicant told nothing would think they have no offer.
          offersFailed = true;
          return of([] as Offer[]);
        })
      ),
    }).pipe(
      finalize(() => {
        this.loading = false;
        this.changeDetectorRef.detectChanges();
      })
    ).subscribe(({ saved, applications, offers }) => {
      if (offersFailed) {
        this.errorMessage = 'Your offers did not load, so any waiting for your answer are not shown. Try again.';
      } else if (failed) {
        this.errorMessage = 'Some of your jobs are taking longer than expected to load. Try again.';
      }
      this.offers = [...offers].sort((a, b) => Number(b.status === 'SENT') - Number(a.status === 'SENT'));
      this.fillColumns(saved, applications);
      this.changeDetectorRef.detectChanges();
    });
  }

  askToAnswer(offer: Offer, accept: boolean): void {
    this.offerMessage = '';
    this.offerError = '';
    this.confirming = { offerId: offer.id, accept };
  }

  cancelAnswer(): void {
    this.confirming = null;
  }

  answer(offer: Offer): void {
    const accept = this.confirming?.accept;
    if (accept === undefined || this.answering) return;
    this.answering = offer.id;
    const answer$ = accept ? this.applicationApi.acceptOffer(offer.id) : this.applicationApi.declineOffer(offer.id);
    answer$.pipe(finalize(() => {
      this.answering = null;
      this.changeDetectorRef.detectChanges();
    })).subscribe({
      next: (answered) => {
        this.confirming = null;
        this.offerMessage = accept
          ? `You accepted. ${answered.companyName || 'The company'} has been told, and will be in touch about your start.`
          : `You declined the offer from ${answered.companyName || 'the company'}.`;
        // The application's stage moves with the answer.
        this.loadSavedJobs();
      },
      error: (error: { status?: number; message?: string }) => {
        this.offerError = error?.message || 'Your answer did not go through. Try again.';
        // The offer changed since this page loaded (withdrawn, expired, or the
        // application moved on): show where it stands now.
        if (error?.status === 409 || error?.status === 404) {
          this.confirming = null;
          this.loadSavedJobs();
        }
      },
    });
  }

  offerStatusLabel(offer: Offer): string {
    const labels: Record<string, string> = {
      SENT: 'Waiting for your answer',
      ACCEPTED: 'You accepted',
      DECLINED: 'You declined',
      EXPIRED: 'Expired',
      WITHDRAWN: 'Withdrawn by the company',
    };
    return labels[offer.status] ?? offer.status;
  }

  money(offer: Offer): string {
    try {
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: offer.currency || 'USD',
        maximumFractionDigits: offer.salary % 1 === 0 ? 0 : 2,
      }).format(offer.salary);
    } catch {
      return `${offer.currency || ''} ${offer.salary}`.trim();
    }
  }

  trackCard(_index: number, card: TrackerCard): string {
    return card.key;
  }

  stageLabel(stage: ApplicantStage): string {
    return STAGE_LABELS[stage] ?? 'Applied';
  }

  updateStatus(job: CandidateSavedJob, newStatus: TrackerStatus): void {
    this.candidateApi.updateSavedJob(job.id!, { status: newStatus }).subscribe({
      next: () => {
        this.say(`Moved to ${this.statusLabel(newStatus)}.`);
        this.loadSavedJobs();
      },
      error: () => this.say('That change did not save. Try again.', true),
    });
  }

  setView(view: 'todo' | 'board'): void {
    this.view = view;
    try {
      localStorage.setItem(VIEW_KEY, view);
    } catch {
      // Private windows can refuse storage; the view just resets next visit.
    }
  }

  get savedCount(): number {
    return this.cards.filter((card) => card.status === 'SAVED' || card.status === 'APPLYING').length;
  }

  get appliedCount(): number {
    return this.cards.filter((card) => ['APPLIED', 'INTERVIEWING', 'OFFER', 'REJECTED'].includes(card.status)).length;
  }

  get interviewingCount(): number {
    return this.cards.filter((card) => card.status === 'INTERVIEWING').length;
  }

  get offerCount(): number {
    return this.cards.filter((card) => card.status === 'OFFER').length;
  }

  /** Needs something from the applicant now: a due step, a decision, an application to finish or follow up. */
  get upNext(): TrackerCard[] {
    return this.cards
      .filter((card) => this.isUpNext(card))
      .sort((a, b) => this.urgency(a) - this.urgency(b));
  }

  get waiting(): TrackerCard[] {
    return this.cards.filter((card) => card.status === 'APPLIED' && !this.isUpNext(card));
  }

  get closed(): TrackerCard[] {
    return this.cards.filter((card) => card.status === 'REJECTED' || card.status === 'ARCHIVED');
  }

  statusLabel(status: string): string {
    return this.statusOptions.find((option) => option.value === status)?.label ?? status;
  }

  /** One line under the title that says what is going on and what comes next. */
  subtitle(card: TrackerCard): string {
    const saved = card.saved;
    if (card.application) {
      const application = card.application;
      return `${this.stageLabel(application.stage)} · applied on AIRRAL ${this.shortDate(application.appliedAt)}`;
    }
    if (!saved) return '';
    const due = this.dueInDays(card);
    if (saved.nextStep && due !== null && due <= 1) {
      return `${saved.nextStep} · ${due < 0 ? 'overdue' : due === 0 ? 'due today' : 'due tomorrow'}`;
    }
    const since = this.daysSince(saved.updatedAt || saved.createdAt);
    switch (card.status) {
      case 'SAVED':
        return saved.job && !this.hasPay(saved) ? 'Saved · pay isn\u2019t listed, ask before applying' : `Saved ${this.shortDate(saved.createdAt)} · apply when you\u2019re ready`;
      case 'APPLYING':
        return saved.nextStep || 'Finish your application';
      case 'APPLIED':
        return since !== null && since >= FOLLOW_UP_AFTER_DAYS
          ? `No reply in ${since} days. Time to follow up.`
          : `Applied · day ${since ?? 0} · follow up ${this.shortDate(this.addDays(saved.updatedAt || saved.createdAt, FOLLOW_UP_AFTER_DAYS))}`;
      case 'INTERVIEWING':
        return saved.nextStep ? `Interviewing · ${saved.nextStep}` : 'Interviewing · review what they ask for';
      case 'OFFER':
        return 'Offer received';
      default:
        return `${this.statusLabel(card.status)} · ${this.shortDate(saved.updatedAt)}`;
    }
  }

  isOverdue(card: TrackerCard): boolean {
    const due = this.dueInDays(card);
    const since = this.daysSince(card.saved?.updatedAt || card.saved?.createdAt);
    return (due !== null && due <= 1) || (card.status === 'APPLIED' && since !== null && since >= FOLLOW_UP_AFTER_DAYS);
  }

  needsFollowUp(card: TrackerCard): boolean {
    const since = this.daysSince(card.saved?.updatedAt || card.saved?.createdAt);
    return !!card.saved && card.status === 'APPLIED' && since !== null && since >= FOLLOW_UP_AFTER_DAYS;
  }

  applyLink(card: TrackerCard): string | null {
    return card.saved?.job?.applyUrl || card.saved?.job?.jobUrl || null;
  }

  toggle(card: TrackerCard): void {
    if (this.expanded.has(card.key)) {
      this.expanded.delete(card.key);
    } else {
      this.expanded.add(card.key);
    }
  }

  onStatusChange(card: TrackerCard, value: string): void {
    if (card.saved && value !== card.status) {
      this.updateStatus(card.saved, value as TrackerStatus);
    }
  }

  /** Next step, due date and notes save when the field is left, one field at a time. */
  saveField(card: TrackerCard, field: 'nextStep' | 'nextStepDueAt' | 'notes', raw: string): void {
    const saved = card.saved;
    if (!saved?.id) return;
    const value = raw.trim();
    const current = field === 'nextStepDueAt' ? (saved.nextStepDueAt || '').slice(0, 10) : (saved[field] || '');
    if (value === current) return;
    if (field === 'nextStepDueAt' && !value) return; // The API keeps a due date once set.
    const request = field === 'nextStepDueAt'
      ? { nextStepDueAt: new Date(`${value}T17:00:00`).toISOString() }
      : { [field]: value };
    this.candidateApi.updateSavedJob(saved.id, request).subscribe({
      next: (updated) => {
        Object.assign(saved, updated);
        this.say('Saved.');
      },
      error: () => this.say('That did not save. Try again.', true),
    });
  }

  copyFollowUp(card: TrackerCard): void {
    const since = this.shortDate(card.saved?.updatedAt || card.saved?.createdAt);
    const text = `Hi, I applied for the ${card.title} role${since ? ` on ${since}` : ''} and wanted to check where things stand. I'm still very interested and happy to share anything else you need. Thank you!`;
    this.copy(text, 'Follow-up message copied. Paste it into an email to the recruiter.');
  }

  copyPayQuestion(card: TrackerCard): void {
    this.copy(
      `Hi, I'm interested in the ${card.title} role at ${card.company}. Could you share the pay range and schedule before I apply? Thank you!`,
      'Question copied.'
    );
  }

  hasPay(saved: CandidateSavedJob): boolean {
    const label = saved.job?.salaryLabel?.toLowerCase() || '';
    return Boolean(label && /[1-9]/.test(label) && !label.includes('not listed'));
  }

  dateInput(value?: string): string {
    return value ? value.slice(0, 10) : '';
  }

  private isUpNext(card: TrackerCard): boolean {
    if (card.status === 'REJECTED' || card.status === 'ARCHIVED') return false;
    if (['SAVED', 'APPLYING', 'INTERVIEWING', 'OFFER'].includes(card.status)) return true;
    return this.isOverdue(card);
  }

  private urgency(card: TrackerCard): number {
    const due = this.dueInDays(card);
    if (card.status === 'OFFER') return -3;
    if (due !== null && due <= 1) return -2 + due / 100;
    if (card.status === 'INTERVIEWING') return -1;
    if (this.needsFollowUp(card)) return 0;
    if (card.status === 'APPLYING') return 1;
    return 2;
  }

  private dueInDays(card: TrackerCard): number | null {
    const due = card.saved?.nextStepDueAt;
    if (!due) return null;
    const time = new Date(due).getTime();
    if (Number.isNaN(time)) return null;
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    return Math.floor((time - today.getTime()) / DAY_MS);
  }

  private daysSince(value?: string): number | null {
    if (!value) return null;
    const time = new Date(value).getTime();
    return Number.isNaN(time) ? null : Math.max(0, Math.floor((Date.now() - time) / DAY_MS));
  }

  private addDays(value: string | undefined, days: number): string | undefined {
    if (!value) return undefined;
    const date = new Date(value);
    date.setDate(date.getDate() + days);
    return date.toISOString();
  }

  private shortDate(value?: string): string {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  }

  private copy(text: string, done: string): void {
    const clipboard = typeof navigator !== 'undefined' ? navigator.clipboard : undefined;
    if (!clipboard) {
      this.say('Copying is not available in this browser.', true);
      return;
    }
    clipboard.writeText(text).then(
      () => this.say(done),
      () => this.say('Copying did not work this time. Try again.', true)
    );
  }

  private say(message: string, error = false): void {
    this.notice = message;
    this.noticeError = error;
    this.changeDetectorRef.detectChanges();
  }

  removeJob(job: CandidateSavedJob): void {
    this.candidateApi.deleteSavedJob(job.id!).subscribe({
      next: () => {
        this.loadSavedJobs();
      },
    });
  }

  private fillColumns(saved: CandidateSavedJob[], applications: MyApplication[]): void {
    // A saved AIRRAL job the applicant has since applied to shows once, as the application.
    const appliedJobIds = new Set(applications.map((application) => application.jobId));
    const savedCards: TrackerCard[] = saved
      .filter((job) => {
        const internalId = internalJobIdOf(job);
        return internalId === null || !appliedJobIds.has(internalId);
      })
      .map((job) => ({
        key: `saved-${job.id}`,
        company: job.job?.companyName || 'Unknown',
        title: job.job?.title || 'Untitled',
        note: job.nextStep,
        saved: job,
        status: (String(job.status || 'SAVED').toUpperCase() as TrackerStatus),
      }));
    const applicationCards: TrackerCard[] = applications.map((application) => ({
      key: `application-${application.id}`,
      company: application.companyName || 'Unknown',
      title: application.jobTitle || 'Untitled',
      application,
      status: COLUMN_FOR_STAGE[application.stage] ?? 'APPLIED',
    }));
    this.cards = [...applicationCards, ...savedCards];

    this.totalJobs = savedCards.length + applicationCards.length;
    this.columns.forEach((col) => {
      col.cards = [
        ...applicationCards.filter((card) => !!card.application && COLUMN_FOR_STAGE[card.application.stage] === col.status),
        ...savedCards.filter((card) => card.saved?.status === col.status),
      ];
    });
  }
}
