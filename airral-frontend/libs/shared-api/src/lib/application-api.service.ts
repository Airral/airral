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

  getMyApplications(applicantId: number): Observable<Application[]> {
    return this.apiClient.get<Application[]>(`/applications/applicant/${applicantId}`);
  }

  getJobApplications(jobId: number): Observable<Application[]> {
    return this.apiClient.get<Application[]>(`/applications/job/${jobId}`);
  }

  getAllApplications(): Observable<Application[]> {
    return this.apiClient.get<Application[]>('/applications');
  }

  updateApplicationStatus(id: number, status: string): Observable<Application> {
    return this.apiClient.put<Application>(`/applications/${id}/status?status=${status}`, {});
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

  scheduleInterview(applicationId: number, interviewDate: string, notes?: string): Observable<Interview> {
    return this.apiClient.post<Interview>('/interviews', { applicationId, interviewDate, notes });
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
