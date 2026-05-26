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
  rating?: number;
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

export interface CandidateJobSummary {
  jobId: string;
  sourceType: string;
  sourceName: string;
  sourceBoardToken?: string;
  externalJobId: string;
  title: string;
  companyName: string;
  companyDomain?: string;
  companyLogoUrl?: string;
  department?: string;
  location?: string;
  workMode?: 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNKNOWN' | string;
  employmentType?: string;
  salaryLabel?: string;
  applyUrl?: string;
  jobUrl?: string;
  applyMode?: 'INTERNAL_APPLY' | 'PARTNER_APPLY' | 'EXTERNAL_APPLY' | string;
  easyApplyAvailable?: boolean;
  sourceUpdatedAt?: string;
  postedLabel?: string;
  matchScore?: number;
  connectionsCount?: number;
  tags?: string[];
  jobQualityScore?: number;
  qualityReasons?: string[];
  totalCompLabel?: string;
  compensationConfidence?: 'POSTED_BASE' | 'NEEDS_BENCHMARK' | string;
}

export interface CandidateJobPageResponse {
  jobs: CandidateJobSummary[];
  limit: number;
  offset: number;
  hasMore: boolean;
  nextOffset?: number | null;
}

export interface CandidateJobDetail extends CandidateJobSummary {
  externalInternalJobId?: string;
  descriptionHtml?: string;
  descriptionText?: string;
  descriptionExcerpt?: string;
  salaryMin?: number;
  salaryMax?: number;
  salaryCurrency?: string;
  sourcePayloadHash?: string;
}

export interface CandidateExperienceEntry {
  company: string;
  title: string;
  startDate: string;
  endDate?: string;
  description?: string;
  current?: boolean;
}

export interface CandidateEducationEntry {
  school: string;
  degree: string;
  field: string;
  graduationYear?: number;
}

export interface CandidateMatchPreferences {
  targetRoles?: string[];
  seniority?: 'ENTRY' | 'MID' | 'SENIOR' | 'STAFF' | 'LEAD' | string;
  searchStatus?: 'ACTIVE' | 'OPEN' | 'CASUAL' | string;
  needsSponsorship?: boolean;
  openToRelocation?: boolean;
  salaryRequired?: boolean;
  easyApplyOnly?: boolean;
  noTakeHome?: boolean;
  directCompanySourceOnly?: boolean;
  stabilityFirst?: boolean;
  mustHaveSkills?: string[];
  niceToHaveSkills?: string[];
  avoidKeywords?: string[];
}

export interface CandidateProfile {
  id: number;
  userId: number;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;

  // Rich profile fields
  headline?: string;
  bio?: string;
  avatarUrl?: string;
  location?: string;
  skills?: string[];
  experience?: CandidateExperienceEntry[];
  education?: CandidateEducationEntry[];

  // Media
  resumeUrl?: string;
  videoIntroUrl?: string;

  // Computed
  profileCompletion?: number;

  // Open-to-work
  openToWork?: boolean;
  preferredEmploymentType?: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP';
  preferredWorkMode?: 'REMOTE' | 'HYBRID' | 'ONSITE';
  salaryExpectationMin?: number;
  salaryExpectationMax?: number;
  salaryCurrency?: string;
  matchPreferences?: CandidateMatchPreferences;

  createdAt?: string;
  updatedAt?: string;
}

export interface UpdateCandidateProfileRequest {
  headline?: string;
  bio?: string;
  avatarUrl?: string;
  location?: string;
  skills?: string[];
  experience?: CandidateExperienceEntry[];
  education?: CandidateEducationEntry[];
  resumeUrl?: string;
  videoIntroUrl?: string;
  openToWork?: boolean;
  preferredEmploymentType?: string;
  preferredWorkMode?: string;
  salaryExpectationMin?: number;
  salaryExpectationMax?: number;
  salaryCurrency?: string;
  matchPreferences?: CandidateMatchPreferences;
}
