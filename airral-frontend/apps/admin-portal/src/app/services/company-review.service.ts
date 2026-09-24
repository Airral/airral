import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from '@airral/shared-api';

export type CompanyReviewStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';

export interface CompanyForReview {
  id: number;
  name: string;
  domain: string | null;
  verificationStatus: CompanyReviewStatus;
  verificationMethod: 'DOMAIN' | 'ADMIN' | null;
  verificationNote: string | null;
  verifiedAt: string | null;
  createdAt: string;
  contactEmail: string | null;
  contactVerified: boolean;
  openJobs: number;
}

/** Admin review of employer companies. Behind /api/admin/**, ADMIN only. */
@Injectable({ providedIn: 'root' })
export class CompanyReviewService {
  private readonly api = inject(ApiClientService);

  list(status: CompanyReviewStatus): Observable<{ status: string; companies: CompanyForReview[] }> {
    return this.api.get<{ status: string; companies: CompanyForReview[] }>(`/admin/companies?status=${status}`);
  }

  approve(id: number, note: string): Observable<{ id: number; verificationStatus: string }> {
    return this.api.post<{ id: number; verificationStatus: string }>(`/admin/companies/${id}/approve`, { note });
  }

  reject(id: number, note: string): Observable<{ id: number; verificationStatus: string }> {
    return this.api.post<{ id: number; verificationStatus: string }>(`/admin/companies/${id}/reject`, { note });
  }
}
