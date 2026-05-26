// libs/shared-api/src/lib/candidate-portal.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import {
  CandidateApplicationView,
  CandidateJobPageResponse,
  CandidateJobDetail,
  CandidateJobSummary,
  CandidateProfile,
  UpdateCandidateProfileRequest
} from '@airral/shared-types';
import { ApplicationApiService } from './application-api.service';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class CandidatePortalService {
  constructor(
    private apiClient: ApiClientService,
    private applicationApiService: ApplicationApiService
  ) {}

  /** Fetch rich candidate profile from backend. Auto-creates if first visit. */
  getCandidateProfile(_email: string): Observable<CandidateProfile> {
    return this.apiClient.get<CandidateProfile>('/candidate/profile');
  }

  /** Partial update — only send the fields you want to change. */
  updateCandidateProfile(request: UpdateCandidateProfileRequest): Observable<CandidateProfile> {
    return this.apiClient.put<CandidateProfile>('/candidate/profile', request);
  }

  uploadCandidateResume(file: File): Observable<CandidateProfile> {
    const formData = new FormData();
    formData.append('file', file);
    return this.apiClient.post<CandidateProfile>('/candidate/profile/resume', formData);
  }

  getRecommendedJobs(limit = 50, boardToken?: string, query?: string): Observable<CandidateJobSummary[]> {
    const params = new URLSearchParams({
      source: 'all',
      limit: String(limit),
    });

    if (boardToken) {
      params.set('board', boardToken);
    }

    if (query?.trim()) {
      params.set('q', query.trim());
    }

    return this.apiClient.get<CandidateJobSummary[]>(`/candidate/jobs/recommended?${params.toString()}`);
  }

  getRecommendedJobsPage(
    limit = 20,
    offset = 0,
    boardToken?: string,
    query?: string
  ): Observable<CandidateJobPageResponse> {
    const params = new URLSearchParams({
      source: 'all',
      limit: String(limit),
      offset: String(Math.max(0, offset)),
    });

    if (boardToken) {
      params.set('board', boardToken);
    }

    if (query?.trim()) {
      params.set('q', query.trim());
    }

    return this.apiClient.get<CandidateJobPageResponse>(`/candidate/jobs/recommended/page?${params.toString()}`);
  }

  getExternalJobDetail(sourceType: string, boardToken: string, jobId: string | number): Observable<CandidateJobDetail> {
    return this.apiClient.get<CandidateJobDetail>(
      `/candidate/jobs/source/${encodeURIComponent(sourceType.toLowerCase())}/${encodeURIComponent(boardToken)}/${encodeURIComponent(String(jobId))}`
    );
  }

  getGreenhouseJobDetail(boardToken: string, jobId: string | number): Observable<CandidateJobDetail> {
    return this.getExternalJobDetail(
      'greenhouse',
      boardToken,
      jobId
    );
  }

  getCandidateApplications(userId: number): Observable<CandidateApplicationView[]> {
    return this.applicationApiService.getMyApplications(userId).pipe(
      map(apps => apps.map(app => this.mapApplicationToView(app)))
    );
  }

  private mapApplicationToView(app: any): CandidateApplicationView {
    return {
      id: app.id,
      jobId: app.jobId,
      jobTitle: app.jobTitle,
      department: app.department || 'Engineering',
      status: app.status,
      appliedAt: app.submittedAt,
      lastUpdated: app.updatedAt,
      interviews: app.interviews || [],
      currentOffer: app.currentOffer
    };
  }
}
