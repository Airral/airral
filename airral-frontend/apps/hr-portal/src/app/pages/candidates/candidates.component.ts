import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApplicationApiService } from '@airral/shared-api';
import { PermissionsService } from '@airral/shared-utils';
import { Application } from '@airral/shared-types';
import { CandidateNotesComponent } from './components/candidate-notes.component';
import { ActivityLogComponent } from './components/activity-log.component';

@Component({
  selector: 'app-hr-candidates',
  standalone: true,
  imports: [CommonModule, FormsModule, CandidateNotesComponent, ActivityLogComponent],
  templateUrl: './candidates.component.html',
  styleUrl: './candidates.component.css',
})
export class CandidatesComponent implements OnInit {
  private applicationApi = inject(ApplicationApiService);
  private permissionsService = inject(PermissionsService);

  applications: Application[] = [];
  loading = true;
  error: string | null = null;

  selectedApplication: Application | null = null;
  searchQuery = '';
  showBelowThreshold = false;  // Toggle for showing low-scored applicants

  ngOnInit(): void {
    this.loadApplications();
  }

  loadApplications(): void {
    this.loading = true;
    this.error = null;

    this.applicationApi.getAllApplications().subscribe({
      next: (applications) => {
        this.applications = applications;
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load applicants';
        this.loading = false;
      }
    });
  }

  get filteredApplications(): Application[] {
    let filtered = this.applications;

    // Filter by ATS threshold (HR can toggle to see all)
    if (!this.showBelowThreshold && this.permissions.canViewAllApplicants) {
      filtered = filtered.filter(app => app.visibleToHR);
    }

    // Search filter
    const query = this.searchQuery.toLowerCase();
    if (query.trim()) {
      filtered = filtered.filter(app =>
        app.applicantName?.toLowerCase().includes(query) ||
        app.applicantEmail.toLowerCase().includes(query) ||
        app.job?.title?.toLowerCase().includes(query)
      );
    }

    // Sort by ATS score (highest first)
    return [...filtered].sort((a, b) => b.atsScore - a.atsScore);
  }

  get belowThresholdCount(): number {
    return this.applications.filter(app => !app.visibleToHR).length;
  }

  get permissions() {
    return this.permissionsService.getPermissions();
  }

  selectApplication(application: Application): void {
    this.selectedApplication = application;
  }

  closeDetail(): void {
    this.selectedApplication = null;
  }

  downloadResume(application: Application): void {
    if (application.resumeUrl) {
      // TODO: Implement actual download
      alert(`Downloading resume for ${application.applicantName}`);
    }
  }

  getScoreColor(score: number): string {
    if (score >= 80) return '#10b981';  // Green
    if (score >= 60) return '#f59e0b';  // Orange
    return '#ef4444';  // Red
  }

  getScoreLabel(score: number): string {
    if (score >= 80) return 'Excellent Match';
    if (score >= 60) return 'Good Match';
    return 'Partial Match';
  }
}
