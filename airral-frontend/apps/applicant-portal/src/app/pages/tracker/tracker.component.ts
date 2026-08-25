import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CandidatePortalService } from '@airral/shared-api';
import { CandidateSavedJob } from '@airral/shared-types';
import { catchError, finalize, of, timeout } from 'rxjs';

type TrackerStatus = 'SAVED' | 'APPLYING' | 'APPLIED' | 'INTERVIEWING' | 'OFFER' | 'REJECTED' | 'ARCHIVED';

interface TrackerColumn {
  status: TrackerStatus;
  label: string;
  icon: string;
  jobs: CandidateSavedJob[];
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
    { status: 'SAVED', label: 'Saved', icon: 'bookmark', jobs: [] },
    { status: 'APPLYING', label: 'Applying', icon: 'edit_note', jobs: [] },
    { status: 'APPLIED', label: 'Applied', icon: 'send', jobs: [] },
    { status: 'INTERVIEWING', label: 'Interviewing', icon: 'groups', jobs: [] },
    { status: 'OFFER', label: 'Offer', icon: 'celebration', jobs: [] },
    { status: 'REJECTED', label: 'Rejected', icon: 'block', jobs: [] },
  ];

  loading = false;
  totalJobs = 0;
  errorMessage = '';

  constructor(
    private readonly candidateApi: CandidatePortalService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnInit(): void {
    this.loadSavedJobs();
  }

  loadSavedJobs(): void {
    this.loading = true;
    this.errorMessage = '';
    this.candidateApi.getSavedJobs().pipe(
      timeout(this.trackerTimeoutMs),
      catchError(() => {
        this.errorMessage = 'Saved jobs are taking longer than expected. Try again.';
        return of([]);
      }),
      finalize(() => {
        this.loading = false;
        this.changeDetectorRef.detectChanges();
      })
    ).subscribe({
      next: (jobs) => {
        this.totalJobs = jobs.length;
        this.columns.forEach((col) => {
          col.jobs = jobs.filter((j) => j.status === col.status);
        });
        this.changeDetectorRef.detectChanges();
      },
      error: () => {
        this.errorMessage = 'Could not load saved jobs. Try again.';
        this.changeDetectorRef.detectChanges();
      },
    });
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
}
