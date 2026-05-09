import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AuthService } from '@airral/shared-auth';
import { CandidatePortalService } from '@airral/shared-api';
import { CandidateApplicationView, CandidateProfile } from '@airral/shared-types';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { forkJoin } from 'rxjs';
import { CandidateFeedComponent } from './components/candidate-feed/candidate-feed.component';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatBadgeModule } from '@angular/material/badge';

type DashboardView = 'feed' | 'applications' | 'profile' | 'tracker';

@Component({
  selector: 'app-candidate-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    CandidateFeedComponent,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatBadgeModule
  ],
  templateUrl: './candidate-dashboard.component.html',
  styleUrls: ['./candidate-dashboard.component.css']
})
export class CandidateDashboardComponent implements OnInit {
  loading = true;
  error: string | null = null;

  activeView: DashboardView = 'feed';
  profile: CandidateProfile | null = null;
  applications: CandidateApplicationView[] = [];

  constructor(
    private authService: AuthService,
    private candidatePortalService: CandidatePortalService
  ) {}

  ngOnInit() {
    const user = this.authService.getCurrentUser();
    if (user?.email) {
      this.loadCandidateData(user.email);
      return;
    }

    window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  }

  setView(view: DashboardView): void {
    this.activeView = view;
  }

  loadCandidateData(email: string): void {
    this.loading = true;
    this.error = null;

    forkJoin({
      profile: this.candidatePortalService.getCandidateProfile(email),
      applications: this.candidatePortalService.getCandidateApplications(email)
    }).subscribe({
      next: (result) => {
        this.profile = result.profile;
        this.applications = result.applications;
        this.loading = false;
      },
      error: (err) => {
        if (String(err?.message || '').toLowerCase().includes('auth')) {
          window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
          return;
        }
        this.error = 'Failed to load your dashboard data';
        this.loading = false;
      }
    });
  }

  openJobsBoard(): void {
    window.location.href = `${PORTAL_ROUTES.WEBSITE}/jobs`;
  }

  logout(): void {
    this.authService.logout();
    window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  }

  getActiveApplicationsCount(): number {
    return this.applications.filter((app) => !app.status.toLowerCase().includes('rejected')).length;
  }

  getInterviewCount(): number {
    return this.applications.reduce((total, app) => total + app.interviews.length, 0);
  }

  getOfferCount(): number {
    return this.applications.filter((app) => !!app.currentOffer).length;
  }

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    });
  }

}
