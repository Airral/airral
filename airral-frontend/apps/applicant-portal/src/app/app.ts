import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '@airral/shared-auth';
import { CandidatePortalService } from '@airral/shared-api';
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
    private readonly candidateApi: CandidatePortalService
  ) {}

  ngOnInit(): void {
    if (this.isLoggedIn) {
      this.loadTrackerBadge();
    }
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
