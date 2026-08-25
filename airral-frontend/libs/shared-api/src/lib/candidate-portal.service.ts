// libs/shared-api/src/lib/candidate-portal.service.ts
import { Injectable } from '@angular/core';
import { Observable, of, throwError } from 'rxjs';
import { catchError, map, shareReplay, tap } from 'rxjs/operators';
import {
  CandidateApplicationView,
  CandidateJobFitRequest,
  CandidateJobFitResult,
  CandidateJobPageResponse,
  CandidateJobDetail,
  CandidateJobSummary,
  CandidateProfile,
  CandidateResumeReview,
  CandidateSavedJob,
  SaveCandidateJobRequest,
  UpdateCandidateSavedJobRequest,
  UpdateCandidateProfileRequest,
  ResumeHealthScore,
  NotificationPreferences,
  UpdateNotificationPreferencesRequest
} from '@airral/shared-types';
import { ApplicationApiService } from './application-api.service';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class CandidatePortalService {
  private candidateProfileRequest$?: Observable<CandidateProfile>;
  private candidateProfileEmail = '';

  constructor(
    private apiClient: ApiClientService,
    private applicationApiService: ApplicationApiService
  ) {}

  /** Fetch rich candidate profile from backend. Auto-creates if first visit. */
  getCandidateProfile(email: string): Observable<CandidateProfile> {
    const normalizedEmail = this.normalizeEmail(email);
    if (this.candidateProfileRequest$ && this.candidateProfileEmail === normalizedEmail) {
      return this.candidateProfileRequest$;
    }

    this.candidateProfileEmail = normalizedEmail;
    this.candidateProfileRequest$ = this.apiClient.get<CandidateProfile>('/candidate/profile').pipe(
      tap((profile) => this.rememberCandidateProfile(profile, normalizedEmail)),
      catchError((error) => {
        this.candidateProfileRequest$ = undefined;
        return throwError(() => error);
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    );

    return this.candidateProfileRequest$;
  }

  /** Partial update — only send the fields you want to change. */
  updateCandidateProfile(request: UpdateCandidateProfileRequest): Observable<CandidateProfile> {
    return this.apiClient.put<CandidateProfile>('/candidate/profile', request).pipe(
      tap((profile) => this.rememberCandidateProfile(profile))
    );
  }

  uploadCandidateResume(file: File): Observable<CandidateProfile> {
    const formData = new FormData();
    formData.append('file', file);
    return this.apiClient.post<CandidateProfile>('/candidate/profile/resume', formData).pipe(
      tap((profile) => this.rememberCandidateProfile(profile))
    );
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
    query?: string,
    maxAgeDays?: number,
    workMode?: string,
    salaryPosted?: boolean,
    experienceLevel?: string,
    visaFriendly?: boolean
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

    if (maxAgeDays && maxAgeDays > 0) {
      params.set('maxAgeDays', String(maxAgeDays));
    }

    if (workMode && workMode !== 'all') {
      params.set('workMode', workMode);
    }

    if (salaryPosted) {
      params.set('salaryPosted', 'true');
    }

    if (experienceLevel && experienceLevel !== 'all') {
      params.set('experienceLevel', experienceLevel);
    }

    if (visaFriendly) {
      params.set('visaFriendly', 'true');
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

  getSavedJobs(): Observable<CandidateSavedJob[]> {
    return this.apiClient.get<CandidateSavedJob[]>('/candidate/saved-jobs');
  }

  saveCandidateJob(request: SaveCandidateJobRequest): Observable<CandidateSavedJob> {
    return this.apiClient.post<CandidateSavedJob>('/candidate/saved-jobs', request);
  }

  updateSavedJob(id: number, request: UpdateCandidateSavedJobRequest): Observable<CandidateSavedJob> {
    return this.apiClient.put<CandidateSavedJob>(`/candidate/saved-jobs/${id}`, request);
  }

  deleteSavedJob(id: number): Observable<void> {
    return this.apiClient.delete<void>(`/candidate/saved-jobs/${id}`);
  }

  runJobFit(request: CandidateJobFitRequest): Observable<CandidateJobFitResult> {
    return this.apiClient.post<CandidateJobFitResult>('/candidate/job-fit', request);
  }

  /** Get instant resume health score (rule-based analysis of uploaded resume). */
  getResumeHealth(): Observable<ResumeHealthScore> {
    return this.apiClient.get<ResumeHealthScore>('/candidate/profile/resume/health');
  }

  /** Get the structured fields extracted from the active resume for applicant review. */
  getResumeReview(): Observable<CandidateResumeReview> {
    return this.apiClient.get<CandidateResumeReview>('/candidate/profile/resume/review');
  }

  /** Get notification preferences for current user. */
  getNotificationPreferences(): Observable<NotificationPreferences> {
    return this.apiClient.get<NotificationPreferences>('/candidate/notifications/preferences');
  }

  /** Update notification preferences. */
  updateNotificationPreferences(request: UpdateNotificationPreferencesRequest): Observable<NotificationPreferences> {
    return this.apiClient.put<NotificationPreferences>('/candidate/notifications/preferences', request);
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

  private rememberCandidateProfile(profile: CandidateProfile, fallbackEmail = this.candidateProfileEmail): void {
    this.candidateProfileEmail = this.normalizeEmail(profile.email || fallbackEmail);
    this.candidateProfileRequest$ = of(profile);
  }

  private normalizeEmail(email: string | undefined): string {
    return (email || '').trim().toLowerCase();
  }
}
