// Unified AIRRAL platform models for Applicant + HR portals.
// These are additive contracts and do not replace existing legacy types yet.

export type ID = number;
export type ISODateTime = string;

export type JobLifecycleStatus = 'DRAFT' | 'OPEN' | 'PAUSED' | 'CLOSED' | 'ARCHIVED';
export type ApplicationLifecycleStatus =
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'SHORTLISTED'
  | 'INTERVIEW_SCHEDULED'
  | 'INTERVIEWED'
  | 'OFFER_EXTENDED'
  | 'HIRED'
  | 'REJECTED'
  | 'ACCEPTED'
  | 'WITHDRAWN';
export type InterviewLifecycleStatus = 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
export type OfferLifecycleStatus = 'DRAFT' | 'SENT' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';

export type FeedPostType = 'COMPANY_SIGNAL' | 'HIRING_PULSE' | 'ROLE_SPOTLIGHT' | 'COMMUNITY_TIP';
export type FeedVisibility = 'PUBLIC' | 'AUTHENTICATED' | 'APPLICANTS_ONLY';
export type FeedReactionType = 'USEFUL' | 'INSPIRING' | 'PRACTICAL';
export type FeedSortBy = 'LATEST' | 'TRENDING';

export interface MoneyValue {
  amount: number;
  currency: string;
}

export interface AuditStamp {
  createdAt: ISODateTime;
  updatedAt: ISODateTime;
}

export interface UserIdentity extends AuditStamp {
  id: ID;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;
  isActive: boolean;
  roles: string[];
}

export interface CandidateProfileModel extends AuditStamp {
  id: ID;
  userId: ID;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;
  location?: string;
  resumeUrl?: string;
  headline?: string;
  skills?: string[];
  yearsOfExperience?: number;
}

export interface CompanyProfileModel extends AuditStamp {
  id: ID;
  name: string;
  handle: string;
  logoUrl?: string;
  followerCount: number;
  industry?: string;
  websiteUrl?: string;
}

export interface JobListingModel extends AuditStamp {
  id: ID;
  title: string;
  description: string;
  department: string;
  status: JobLifecycleStatus;
  createdByUserId?: ID;
  createdByEmail?: string;
  companyId?: ID;
  location?: string;
  employmentType?: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP';
  workMode?: 'REMOTE' | 'HYBRID' | 'ONSITE';
  salaryRange?: {
    min?: number;
    max?: number;
    currency: string;
  };
  tags?: string[];
}

export interface ApplicationModel extends AuditStamp {
  id: ID;
  jobId: ID;
  applicantId: ID;
  applicantEmail: string;
  status: ApplicationLifecycleStatus;
  coverLetter?: string;
  resumeUrl?: string;
  submittedAt: ISODateTime;
}

export interface InterviewModel {
  id: ID;
  applicationId: ID;
  scheduledByUserId?: ID;
  candidateName?: string;
  candidateEmail?: string;
  jobTitle?: string;
  interviewDate: ISODateTime;
  status: InterviewLifecycleStatus;
  feedback?: string;
  rating?: number;
  createdAt: ISODateTime;
}

export interface OfferModel extends AuditStamp {
  id: ID;
  applicationId: ID;
  jobId: ID;
  candidateName: string;
  candidateEmail: string;
  jobTitle: string;
  compensation: MoneyValue;
  startDate: ISODateTime;
  offerLetter: string;
  benefits?: string;
  contingencies?: string;
  status: OfferLifecycleStatus;
  sentAt?: ISODateTime;
  expiresAt?: ISODateTime;
  respondedAt?: ISODateTime;
}

export interface FeedAttachment {
  id: ID;
  type: 'IMAGE' | 'VIDEO' | 'LINK' | 'DOCUMENT' | 'CHART';
  url?: string;
  title?: string;
  description?: string;
  thumbnailUrl?: string;
}

export interface FeedEngagement {
  usefulCount: number;
  inspiringCount: number;
  practicalCount: number;
  responseCount: number;
  followerActions: number;
}

export interface CompanyFeedPostModel extends AuditStamp {
  id: ID;
  companyId: ID;
  companyName: string;
  companyHeadline: string;
  postType: FeedPostType;
  visibility: FeedVisibility;
  topic: string;
  content: string;
  publishedAt: ISODateTime;
  attachments?: FeedAttachment[];
  engagement: FeedEngagement;
  viewerReaction?: FeedReactionType;
}

export interface FeedQueryModel {
  page?: number;
  pageSize?: number;
  sortBy?: FeedSortBy;
  topic?: string;
  companyId?: ID;
  postTypes?: FeedPostType[];
}

export interface PageMetaModel {
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export interface FeedPageModel {
  items: CompanyFeedPostModel[];
  meta: PageMetaModel;
}

export interface CandidateApplicationViewModel {
  id: ID;
  jobId: ID;
  jobTitle: string;
  department: string;
  status: ApplicationLifecycleStatus;
  submittedAt: ISODateTime;
  updatedAt: ISODateTime;
  nextStep?: string;
  nextStepDate?: ISODateTime;
  interviews: InterviewModel[];
  currentOffer?: OfferModel;
}

export interface CandidateDashboardModel {
  profile: CandidateProfileModel;
  applications: CandidateApplicationViewModel[];
  companyFeed: CompanyFeedPostModel[];
  recommendedJobs: JobListingModel[];
}

export interface HrPipelineSummaryModel {
  jobId: ID;
  countsByStatus: Record<ApplicationLifecycleStatus, number>;
  totalInterviewsScheduled: number;
  totalOffersSent: number;
  totalHires: number;
}

export interface HrDashboardModel {
  openJobs: JobListingModel[];
  recentApplications: ApplicationModel[];
  upcomingInterviews: InterviewModel[];
  activeOffers: OfferModel[];
  pipelineSummaries: HrPipelineSummaryModel[];
}
