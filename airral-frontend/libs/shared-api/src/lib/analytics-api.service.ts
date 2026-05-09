// libs/shared-api/src/lib/analytics-api.service.ts
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

export interface DashboardMetrics {
  totalJobs: number;
  openJobs: number;
  closedJobs: number;
  totalApplications: number;
  totalInterviews: number;
  upcomingInterviews: number;
  completedInterviews: number;
  totalOffers: number;
  offersAccepted: number;
  offersPending: number;
  totalHires: number;
  offerAcceptanceRate: number;
  applicationToInterviewRate: number;
  averageTimeToHire: number;
}

export interface PipelineAnalytics {
  applicationsReceived: number;
  applicationsReviewed: number;
  applicationsRejected: number;
  interviewsScheduled: number;
  offersSent: number;
  offersAccepted: number;
  hires: number;
  applicationToInterviewRate: number;
  interviewToOfferRate: number;
  offerToHireRate: number;
  applicationsByStatus: Record<string, number>;
}

@Injectable({
  providedIn: 'root'
})
export class AnalyticsApiService {
  private apiClient = inject(ApiClientService);

  getDashboardMetrics(): Observable<DashboardMetrics> {
    return this.apiClient.get<DashboardMetrics>('/analytics/dashboard');
  }

  getPipelineAnalytics(): Observable<PipelineAnalytics> {
    return this.apiClient.get<PipelineAnalytics>('/analytics/pipeline');
  }
}
