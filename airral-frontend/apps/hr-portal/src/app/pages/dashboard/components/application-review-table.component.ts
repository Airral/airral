import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { Application } from '@airral/shared-types';

export interface StatusUpdateEvent {
  appId: number;
  status: string;
}

@Component({
  selector: 'app-application-review-table',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './application-review-table.component.html',
  styleUrl: './application-review-table.component.css',
})
export class ApplicationReviewTableComponent {
  @Input() loading = false;
  @Input() errorMessage = '';
  @Input() filters: string[] = [];
  @Input() selectedFilter = 'all';
  @Input() applications: Application[] = [];

  @Output() filterChange = new EventEmitter<string>();
  @Output() statusUpdate = new EventEmitter<StatusUpdateEvent>();
  @Output() retry = new EventEmitter<void>();

  get filteredApplications(): Application[] {
    if (this.selectedFilter === 'all') {
      return this.applications;
    }

    const mappedStatus = this.selectedFilter.toUpperCase();
    return this.applications.filter((application) => application.status === mappedStatus);
  }

  getStatusColor(status: string): string {
    const colors: Record<string, string> = {
      SUBMITTED: '#2563eb',
      UNDER_REVIEW: '#d97706',
      SHORTLISTED: '#15803d',
      INTERVIEW_SCHEDULED: '#7c3aed',
      INTERVIEWED: '#0f766e',
      REJECTED: '#dc2626',
      HIRED: '#16a34a',
      ACCEPTED: '#16a34a',
    };

    return colors[status] || '#64748b';
  }

  onUpdate(appId: number, status: string): void {
    this.statusUpdate.emit({ appId, status });
  }

  formatFilterLabel(filter: string): string {
    return filter.replace(/_/g, ' ');
  }
}
