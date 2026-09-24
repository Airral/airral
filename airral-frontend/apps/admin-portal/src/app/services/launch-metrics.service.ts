import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from '@airral/shared-api';

export interface LaunchTraffic {
  visitorDays: number;
  applicantVisitorDays: number;
  websiteVisitorDays: number;
  pageViews: number;
  applyClicks: number;
}

export interface LaunchFunnel {
  signedUp: number;
  verified: number;
  uploadedResume: number;
  ranMatch: number;
  savedJob: number;
  clickedApply: number;
  trackedApplied: number;
  testAccountsHidden: number;
}

export interface LaunchEmployers {
  signedUp: number;
  companiesWaitingForReview: number;
  verifiedCompanies: number;
  openEmployerJobs: number;
}

export interface LaunchDay {
  day: string;
  visitors: number;
  signups: number;
  applyClicks: number;
}

export interface LaunchApplicant {
  id: number;
  email: string;
  name: string | null;
  signedUpAt: string;
  lastLoginAt: string | null;
  lastSeenAt: string | null;
  verified: boolean;
  resumes: number;
  matches: number;
  savedJobs: number;
  applyClicks: number;
}

export interface LaunchMetrics {
  windowDays: number;
  since: string;
  traffic: LaunchTraffic;
  funnel: LaunchFunnel;
  employers: LaunchEmployers;
  daily: LaunchDay[];
  recentApplicants: LaunchApplicant[];
  topReferrers: { host: string; visits: number }[];
}

/** The admin Launch page. Behind /api/admin/**, ADMIN only. */
@Injectable({ providedIn: 'root' })
export class LaunchMetricsService {
  private readonly api = inject(ApiClientService);

  load(days: number): Observable<LaunchMetrics> {
    return this.api.get<LaunchMetrics>(`/admin/analytics/launch?days=${days}`);
  }
}
