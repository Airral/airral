// libs/shared-types/src/lib/candidate-portal.types.ts

export interface CandidateApplicationView {
  id: number;
  jobId: number;
  jobTitle: string;
  department: string;
  status: string;
  appliedAt: string;
  lastUpdated: string;
  nextStep?: string;
  nextStepDate?: string;
  interviews: CandidateInterviewView[];
  currentOffer?: CandidateOfferView;
}

export interface CandidateInterviewView {
  id: number;
  stage: string;
  scheduledDate?: string;
  interviewers?: string[];
  feedback?: string;
  rating?: number;  // 1-5 scale interview rating
  status: 'SCHEDULED' | 'COMPLETED' | 'PENDING';
}

export interface CandidateOfferView {
  id: number;
  jobTitle: string;
  salary: number;
  currency: string;
  startDate: string;
  status: 'SENT' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED';
  sentAt: string;
  expiresAt: string;
  acceptanceDeadline: string;
}

export interface CandidateProfile {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;
  location?: string;
  resume?: string;
}
