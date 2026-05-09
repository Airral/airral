// libs/shared-types/src/lib/application.types.ts

// Note: Application and ApplicationStatus are now exported from hiring.types.ts
// This file contains Offer-related types only

export interface Offer {
  id: number;
  applicationId: number;
  jobId: number;
  candidateName: string;
  candidateEmail: string;
  jobTitle: string;
  salary: number;
  currency: string;
  startDate: string;
  offerLetter: string;
  benefits: string;
  contingencies: string; // e.g., "Background check clearance"
  status: OfferStatus;
  sentAt?: string;
  expiresAt?: string;
  respondedAt?: string;
  createdAt: string;
  updatedAt: string;
}

export const OfferStatus = {
  DRAFT: 'DRAFT',
  SENT: 'SENT',
  ACCEPTED: 'ACCEPTED',
  DECLINED: 'DECLINED',
  EXPIRED: 'EXPIRED',
  WITHDRAWN: 'WITHDRAWN',
} as const;
export type OfferStatus = (typeof OfferStatus)[keyof typeof OfferStatus];

export interface CreateOfferRequest {
  applicationId: number;
  jobId: number;
  salary: number;
  currency: string;
  startDate: string;
  offerLetter: string;
  benefits: string;
  contingencies?: string;
}

export interface SendOfferRequest {
  offerId: number;
  expiresInDays: number;
}
