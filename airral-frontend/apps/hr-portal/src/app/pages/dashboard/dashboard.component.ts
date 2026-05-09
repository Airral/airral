// apps/hr-portal/src/app/pages/dashboard/dashboard.component.ts
import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Application } from '@airral/shared-types';
import { ApplicationApiService } from '@airral/shared-api';
import { finalize, timeout } from 'rxjs/operators';
import { KpiCardsComponent } from './components/kpi-cards.component';
import { PriorityQueueComponent } from './components/priority-queue.component';
import { PipelineMixComponent } from './components/pipeline-mix.component';
import {
  ApplicationReviewTableComponent,
  StatusUpdateEvent,
} from './components/application-review-table.component';

@Component({
  selector: 'app-hr-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    KpiCardsComponent,
    PriorityQueueComponent,
    PipelineMixComponent,
    ApplicationReviewTableComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  private readonly applicationService = inject(ApplicationApiService);

  applications: Application[] = [];
  loading = false;
  errorMessage = '';
  selectedFilter = 'all';
  readonly filters = ['all', 'submitted', 'under_review', 'shortlisted', 'interviewed', 'hired', 'rejected'];

  ngOnInit() {
    this.loadApplications();
  }

  loadApplications() {
    this.loading = true;
    this.errorMessage = '';

    this.applicationService.getAllApplications().pipe(
      timeout(15000),
      finalize(() => {
        this.loading = false;
      })
    ).subscribe({
      next: (data) => {
        this.applications = data;
      },
      error: () => {
        this.applications = [];
        this.errorMessage = 'No applications are available right now.';
      }
    });
  }

  updateStatus(appId: number, status: string) {
    this.applicationService.updateApplicationStatus(appId, status).subscribe({
      next: () => {
        this.loadApplications();
      },
      error: () => {
        this.errorMessage = 'No applications are available right now.';
      }
    });
  }

  onFilterChange(filter: string): void {
    this.selectedFilter = filter;
  }

  onStatusUpdate(event: StatusUpdateEvent): void {
    this.updateStatus(event.appId, event.status);
  }

  countByStatus(status: string): number {
    return this.applications.filter((application) => application.status === status).length;
  }
}
