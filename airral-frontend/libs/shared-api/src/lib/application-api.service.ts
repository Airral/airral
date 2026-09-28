// libs/shared-api/src/lib/application-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Application,
  ApplicationStatus,
  SubmitApplicationRequest,
  Offer,
  CreateOfferRequest,
  SendOfferRequest,
  Interview,
  MyApplication,
  ScheduleInterviewRequest,
  Scorecard,
  ScorecardRequest,
} from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class ApplicationApiService {
  constructor(private apiClient: ApiClientService) {}

  submitApplication(request: SubmitApplicationRequest): Observable<Application> {
    return this.apiClient.post<Application>('/applications', request);
  }

  getApplicationById(id: number): Observable<Application> {
    return this.apiClient.get<Application>(`/applications/${id}`);
  }

  /** The resume attached to an application, for the company reviewing it. */
  downloadResume(applicationId: number): Observable<Blob> {
    return this.apiClient.getBlob(`/applications/${applicationId}/resume`);
  }

  /** The signed-in applicant's own applications, with the stage each is at. */
  getMyApplications(applicantId: number): Observable<MyApplication[]> {
    return this.apiClient.get<MyApplication[]>(`/applications/applicant/${applicantId}`);
  }

  getJobApplications(jobId: number): Observable<Application[]> {
    return this.apiClient.get<Application[]>(`/applications/job/${jobId}`);
  }

  getAllApplications(): Observable<Application[]> {
    return this.apiClient.get<Application[]>('/applications');
  }

  /** With notifyCandidate, turning a candidate down emails them. */
  updateApplicationStatus(id: number, status: string, notifyCandidate = false): Observable<Application> {
    const notify = notifyCandidate ? '&notifyCandidate=true' : '';
    return this.apiClient.put<Application>(`/applications/${id}/status?status=${status}${notify}`, {});
  }

  hire(id: number): Observable<Application> {
    return this.updateApplicationStatus(id, ApplicationStatus.HIRED);
  }

  extendOffer(id: number): Observable<Application> {
    return this.updateApplicationStatus(id, ApplicationStatus.OFFER_EXTENDED);
  }

  reject(id: number): Observable<Application> {
    return this.updateApplicationStatus(id, ApplicationStatus.REJECTED);
  }

  /** With notifyCandidate, the candidate is emailed the day and time. */
  scheduleInterview(request: ScheduleInterviewRequest): Observable<Interview> {
    return this.apiClient.post<Interview>('/interviews', request);
  }

  /** The interviews the signed-in teammate is on as an interviewer. */
  getMyInterviews(): Observable<Interview[]> {
    return this.apiClient.get<Interview[]>('/interviews/mine');
  }

  /** The signed-in interviewer's own scorecard for an interview: saved, or blank. */
  getMyScorecard(interviewId: number): Observable<Scorecard> {
    return this.apiClient.get<Scorecard>(`/interviews/${interviewId}/scorecard`);
  }

  saveMyScorecard(interviewId: number, request: ScorecardRequest): Observable<Scorecard> {
    return this.apiClient.put<Scorecard>(`/interviews/${interviewId}/scorecard`, request);
  }

  /** The resume of the candidate in an interview the signed-in teammate is on. */
  downloadInterviewResume(interviewId: number): Observable<Blob> {
    return this.apiClient.getBlob(`/interviews/${interviewId}/resume`);
  }

  /** The submitted scorecards for an application. */
  getScorecards(applicationId: number): Observable<Scorecard[]> {
    return this.apiClient.get<Scorecard[]>(`/applications/${applicationId}/scorecards`);
  }

  getInterviewsByApplication(applicationId: number): Observable<Interview[]> {
    return this.apiClient.get<Interview[]>(`/interviews/application/${applicationId}`);
  }

  getAllInterviews(): Observable<Interview[]> {
    return this.apiClient.get<Interview[]>('/interviews');
  }

  submitInterviewFeedback(interviewId: number, feedback: string, rating: number): Observable<Interview> {
    return this.apiClient.put<Interview>(`/interviews/${interviewId}/feedback`, { feedback, rating });
  }

  createOffer(request: CreateOfferRequest): Observable<Offer> {
    return this.apiClient.post<Offer>('/offers', request);
  }

  getOfferById(id: number): Observable<Offer> {
    return this.apiClient.get<Offer>(`/offers/${id}`);
  }

  getOffersByApplication(applicationId: number): Observable<Offer[]> {
    return this.apiClient.get<Offer[]>(`/offers/application/${applicationId}`);
  }

  getAllOffers(): Observable<Offer[]> {
    return this.apiClient.get<Offer[]>('/offers');
  }

  sendOffer(request: SendOfferRequest): Observable<Offer> {
    return this.apiClient.post<Offer>(`/offers/${request.offerId}/send`, request);
  }

  /** The signed-in applicant's own offers, once sent. */
  getMyOffers(): Observable<Offer[]> {
    return this.apiClient.get<Offer[]>('/offers/mine');
  }

  /** An applicant accepts their offer, or HR records that a candidate it added by hand accepted. */
  acceptOffer(offerId: number): Observable<Offer> {
    return this.apiClient.post<Offer>(`/offers/${offerId}/accept`, {});
  }

  declineOffer(offerId: number): Observable<Offer> {
    return this.apiClient.post<Offer>(`/offers/${offerId}/decline`, {});
  }

  updateOffer(id: number, partial: Partial<Offer>): Observable<Offer> {
    return this.apiClient.put<Offer>(`/offers/${id}`, partial);
  }

  withdrawOffer(offerId: number): Observable<Offer> {
    return this.apiClient.post<Offer>(`/offers/${offerId}/withdraw`, {});
  }

  getUpcomingInterviews(): Observable<any[]> {
    return this.apiClient.get<any[]>('/interviews/upcoming');
  }

  getInterviewCalendar(): Observable<any> {
    return this.apiClient.get<any>('/interviews/calendar');
  }
}
