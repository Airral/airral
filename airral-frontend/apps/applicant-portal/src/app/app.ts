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

    // Not on a page that works without signing in. isAuthenticated() cannot be
    // trusted here: the backend token is an encrypted JWE, so the client cannot
    // read its expiry and isTokenExpired returns false for it unconditionally --
    // meaning anyone who ever signed in on this browser looks logged in forever
    // while the server expires them after 24h. Firing this badge request on the
    // unsubscribe page therefore produced a 401 seconds after it rendered, and
    // the 401 handler signed the reader out and redirected them to /login.
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
