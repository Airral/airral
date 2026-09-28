import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApplicationApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { Interview } from '@airral/shared-types';
import { wallTimeToDate } from '@airral/shared-utils';
import { finalize } from 'rxjs';

/**
 * The interviews the signed-in teammate is on. Every role on the hiring team
 * can be an interviewer, so this is where an interviewer's work starts.
 */
@Component({
  selector: 'app-my-interviews',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './my-interviews.component.html',
  styleUrl: './my-interviews.component.css',
})
export class MyInterviewsComponent implements OnInit {
  private readonly applicationApi = inject(ApplicationApiService);
  private readonly auth = inject(AuthService);

  upcoming: Interview[] = [];
  past: Interview[] = [];
  loading = true;
  error = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.applicationApi
      .getMyInterviews()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (interviews) => {
          const now = Date.now();
          const byStart = (a: Interview, b: Interview) => this.startOf(a).getTime() - this.startOf(b).getTime();
          this.upcoming = interviews.filter((interview) => this.endOf(interview) >= now && interview.status !== 'CANCELLED').sort(byStart);
          this.past = interviews.filter((interview) => !this.upcoming.includes(interview)).sort(byStart).reverse();
        },
        error: (error: Error) => {
          this.error = error.message || 'Your interviews could not be loaded.';
        },
      });
  }

  startOf(interview: Interview): Date {
    return wallTimeToDate(interview.interviewDate, interview.timeZone);
  }

  private endOf(interview: Interview): number {
    return this.startOf(interview).getTime() + (interview.durationMinutes || 60) * 60_000;
  }

  /** The other people on the interview, besides the viewer. */
  alongside(interview: Interview): string {
    const me = this.auth.getCurrentUser()?.id;
    return (interview.interviewers ?? [])
      .filter((interviewer) => interviewer.id !== me)
      .map((interviewer) => interviewer.name)
      .join(', ');
  }
}
