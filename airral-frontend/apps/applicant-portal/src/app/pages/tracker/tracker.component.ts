import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationApiService, CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { ApplicantStage, CandidateSavedJob, MyApplication, Offer } from '@airral/shared-types';
import { catchError, finalize, forkJoin, of, timeout } from 'rxjs';

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
}

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
  imports: [CommonModule],
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
        this.loadSavedJobs();
      },
    });
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
      }));
    const applicationCards: TrackerCard[] = applications.map((application) => ({
      key: `application-${application.id}`,
      company: application.companyName || 'Unknown',
      title: application.jobTitle || 'Untitled',
      application,
    }));

    this.totalJobs = savedCards.length + applicationCards.length;
    this.columns.forEach((col) => {
      col.cards = [
        ...applicationCards.filter((card) => !!card.application && COLUMN_FOR_STAGE[card.application.stage] === col.status),
        ...savedCards.filter((card) => card.saved?.status === col.status),
      ];
    });
  }
}
