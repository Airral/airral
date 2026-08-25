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
  matchReasons?: string[];
  connectionsCount?: number;
  tags?: string[];
  jobQualityScore?: number;
  qualityReasons?: string[];
  totalCompLabel?: string;
  compensationConfidence?: 'POSTED_BASE' | 'NEEDS_BENCHMARK' | string;
  sponsorshipLanguage?: 'SPONSORS' | 'NO_SPONSORSHIP' | 'AUTHORIZATION_REQUIRED' | 'UNKNOWN' | string;
  visaConfidenceScore?: number;
  visaReasons?: string[];
  requiresUsWorkAuthorization?: boolean;
  contractOrStaffingRisk?: boolean;
  stemOptRisk?: boolean;
  h1bTransferFit?: boolean;
  capExemptFit?: boolean;
  seniorityLabel?: string;
  experienceYears?: number;
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
  workAuthorizationStatus?: 'US_CITIZEN' | 'GREEN_CARD' | 'H1B' | 'H1B_TRANSFER' | 'F1_OPT' | 'F1_STEM_OPT' | 'H4_EAD' | 'TN' | 'E3' | 'O1' | 'OTHER' | 'UNSPECIFIED' | string;
  needsSponsorshipNow?: boolean;
  needsSponsorshipLater?: boolean;
  requiresEVerify?: boolean;
  workAuthorizationExpiresAt?: string;
  openToCapExemptEmployers?: boolean;
  visaNotes?: string;
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
  activeResumeDocumentId?: number;
  resumeUrl?: string;
  resumeParseStatus?: 'PARSED' | 'PARSE_FAILED' | 'UPLOADED' | string;
  resumeParsedAt?: string;
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

export interface CandidateResumeReview {
  resumeDocumentId: number;
  parseStatus: 'PARSED' | 'PARSE_FAILED' | 'UPLOADED' | string;
  headline?: string;
  summary?: string;
  location?: string;
  skills: string[];
  experience: CandidateExperienceEntry[];
  education: CandidateEducationEntry[];
  suggestedTargetRoles: string[];
  suggestedWorkMode?: string;
  parseConfidenceScore?: number;
  parseWarnings: string[];
  experienceYears?: number;
  parsedAt?: string;
}

export interface CandidateJobFitResult {
  id: number;
  sourceJobKey: string;
  resumeDocumentId?: number;
  fitScore: number;
  visaFitScore?: number;
  matchedRequirements: string[];
  missingRequirements: string[];
  keywordGaps: string[];
  weakBullets: string[];
  suggestedRewrites: string[];
  applicationChecklist: string[];
  job?: CandidateJobDetail;
  generatedAt?: string;
}

export interface CandidateSavedJob {
  id: number;
  sourceJobKey: string;
  status: 'SAVED' | 'APPLYING' | 'APPLIED' | 'INTERVIEWING' | 'OFFER' | 'REJECTED' | 'ARCHIVED' | string;
  resumeDocumentId?: number;
  fitResultId?: number;
  nextStep?: string;
  nextStepDueAt?: string;
  notes?: string;
  job?: CandidateJobSummary;
  fitResult?: CandidateJobFitResult;
  createdAt?: string;
  updatedAt?: string;
}

export interface SaveCandidateJobRequest {
  sourceJobKey: string;
  status?: string;
  resumeDocumentId?: number;
  nextStep?: string;
  nextStepDueAt?: string;
  notes?: string;
}

export interface UpdateCandidateSavedJobRequest {
  status?: string;
  resumeDocumentId?: number;
  fitResultId?: number;
  nextStep?: string;
  nextStepDueAt?: string;
  notes?: string;
}

export interface CandidateJobFitRequest {
  sourceJobKey: string;
  resumeDocumentId?: number;
}

// Resume Health Score (instant analysis after upload)
export interface ResumeHealthScore {
  score: number;
  grade: 'A' | 'B' | 'C' | 'D' | 'F';
  categories: Record<string, ResumeHealthCategory>;
  issues: ResumeHealthIssue[];
  topFixes: string[];
  wordCount: number;
  skillCount: number;
}

export interface ResumeHealthCategory {
  label: string;
  score: number;
  maxScore: number;
}

export interface ResumeHealthIssue {
  code: string;
  severity: 'critical' | 'warning' | 'info';
  message: string;
}

// Notification Preferences
export interface NotificationPreferences {
  jobAlertEnabled: boolean;
  followUpReminderEnabled: boolean;
  weeklyDigestEnabled: boolean;
  resumeNudgeEnabled: boolean;
  savedJobChangeEnabled: boolean;
}

export interface UpdateNotificationPreferencesRequest {
  jobAlertEnabled?: boolean;
  followUpReminderEnabled?: boolean;
  weeklyDigestEnabled?: boolean;
  resumeNudgeEnabled?: boolean;
  savedJobChangeEnabled?: boolean;
}
