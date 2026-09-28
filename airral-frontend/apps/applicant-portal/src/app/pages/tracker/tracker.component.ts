import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApplicationApiService, CandidatePortalService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { ApplicantStage, CandidateSavedJob, MyApplication } from '@airral/shared-types';
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
    }).pipe(
      finalize(() => {
        this.loading = false;
        this.changeDetectorRef.detectChanges();
      })
    ).subscribe(({ saved, applications }) => {
      if (failed) {
        this.errorMessage = 'Some of your jobs are taking longer than expected to load. Try again.';
      }
      this.fillColumns(saved, applications);
      this.changeDetectorRef.detectChanges();
    });
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
      .filter((job) => !(job.job?.sourceType === 'AIRRAL_INTERNAL' && appliedJobIds.has(Number(job.job?.externalJobId))))
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
