// apps/applicant-portal/src/app/pages/dashboard/dashboard.component.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Application } from '@airral/shared-types';
import { ApplicationApiService } from '@airral/shared-api';
import { PORTAL_ROUTES } from '@airral/shared-utils';

@Component({
  selector: 'app-applicant-dashboard',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  applications: Application[] = [];
  loading = false;
  currentUser: any = { id: 3, firstName: 'John', lastName: 'Doe', email: 'applicant@airral.com' };
  readonly websiteJobsUrl = `${PORTAL_ROUTES.WEBSITE}/jobs`;
  readonly profileUrl = `${PORTAL_ROUTES.WEBSITE}/apply`;
  readonly helpUrl = `${PORTAL_ROUTES.WEBSITE}/help`;

  constructor(private applicationService: ApplicationApiService) {}

  countByStatus(status: string): number {
    return this.applications.filter(a => a.status === status).length;
  }

  ngOnInit() {
    this.loadApplications();
  }

  loadApplications() {
    this.loading = true;
    if (this.currentUser) {
      this.applicationService.getMyApplications(this.currentUser.id).subscribe({
        next: (data) => {
          this.applications = data;
          this.loading = false;
        },
        error: () => {
          this.loading = false;
        }
      });
    }
  }

  getStatusColor(status: string): string {
    const colors: any = {
      'SUBMITTED': '#2196F3',
      'UNDER_REVIEW': '#FF9800',
      'SHORTLISTED': '#8BC34A',
      'REJECTED': '#F44336',
      'ACCEPTED': '#4CAF50'
    };
    return colors[status] || '#999';
  }

  get activeApplications(): number {
    return this.applications.filter((application) => !['REJECTED', 'HIRED'].includes(application.status)).length;
  }
}
