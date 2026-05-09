// libs/shared-api/src/lib/referral-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

export interface CreateReferralRequest {
  referredName: string;
  referredEmail: string;
  referredPhone?: string;
  jobId?: number;
  notes?: string;
  relationship?: string;
  resumeUrl?: string;
}

export interface Referral {
  id: number;
  referredById: number;
  referredByName: string;
  referredName: string;
  referredEmail: string;
  referredPhone?: string;
  jobId?: number;
  jobTitle?: string;
  status: string;
  submittedAt: string;
  reviewedAt?: string;
  reviewedById?: number;
  resumeUrl?: string;
  notes?: string;
  relationship?: string;
  applicationId?: number;
  bonusAmount?: number;
  bonusPaidAt?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ReferralApiService {
  constructor(private apiClient: ApiClientService) {}

  /**
   * Submit an employee referral
   */
  submitReferral(request: CreateReferralRequest): Observable<Referral> {
    return this.apiClient.post<Referral>('/referrals', request);
  }

  /**
   * Get my referrals (user's own)
   */
  getMyReferrals(): Observable<Referral[]> {
    return this.apiClient.get<Referral[]>('/referrals');
  }

  /**
   * Get all referrals for the organization (HR only)
   */
  getOrganizationReferrals(): Observable<Referral[]> {
    return this.apiClient.get<Referral[]>('/referrals/organization');
  }

  /**
   * Get referral by ID
   */
  getReferralById(id: number): Observable<Referral> {
    return this.apiClient.get<Referral>(`/referrals/${id}`);
  }

  /**
   * Update referral status (HR only)
   */
  updateReferralStatus(id: number, status: string): Observable<Referral> {
    return this.apiClient.put<Referral>(`/referrals/${id}/status?status=${status}`, {});
  }

  /**
   * Convert referral to application (HR only)
   */
  convertToApplication(id: number): Observable<any> {
    return this.apiClient.post<any>(`/referrals/${id}/application`, {});
  }
}
