import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '@airral/shared-auth';
import { CandidatePortalService } from '@airral/shared-api';
import { VisitorSignalService } from '@airral/shared-utils';
import { catchError, of } from 'rxjs';

@Component({
  imports: [CommonModule, RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected title = 'applicant-portal';
  trackerBadge = 0;

  constructor(
    protected readonly auth: AuthService,
    private readonly router: Router,
    private readonly candidateApi: CandidatePortalService,
    private readonly visitorSignals: VisitorSignalService
  ) {}

  ngOnInit(): void {
    this.visitorSignals.trackPageViews('applicant');

    // Not on a page that works without signing in.
    //
    // This was load-bearing: isAuthenticated() could not be trusted, because
    // the client had no way to read an encrypted token's expiry and reported
    // every past sign-in as current. Firing this badge request on the
    // unsubscribe page produced a 401 seconds after it rendered, and the 401
    // handler signed the reader out and redirected them away mid-task.
    //
    // TokenService now knows when a session ends, so isLoggedIn is answerable
    // and that specific failure is gone. The check stays anyway: a page reached
    // from an email has no reason to ask an authenticated question, and a
    // session the server has disowned inside its own expiry -- tokenVersion
    // moved on -- still 401s here with nothing in the client able to predict it.
    if (this.isLoggedIn && !this.onPublicPage()) {
      this.loadTrackerBadge();
    }
  }

  /** Pages reachable from an email, where no session is assumed. */
  private onPublicPage(): boolean {
    if (typeof window === 'undefined') {
      return false;
    }
    return window.location.pathname.startsWith('/unsubscribe');
  }

  get isLoggedIn(): boolean {
    return this.auth.isAuthenticated();
  }

  get userInitials(): string {
    const user = this.auth.getCurrentUser();
    if (!user) return '?';
    const first = user.firstName?.charAt(0) || '';
    const last = user.lastName?.charAt(0) || '';
    return (first + last).toUpperCase() || user.email?.charAt(0).toUpperCase() || '?';
  }

  logout(): void {
    this.auth.logout();
    this.router.navigateByUrl('/login');
  }

  private loadTrackerBadge(): void {
    this.candidateApi.getSavedJobs().pipe(
      catchError(() => of([]))
    ).subscribe((jobs) => {
      const now = new Date();
      this.trackerBadge = jobs.filter(job =>
        job.nextStepDueAt
        && new Date(job.nextStepDueAt) < now
        && job.status !== 'REJECTED'
        && job.status !== 'ARCHIVED'
      ).length;
    });
  }
}
