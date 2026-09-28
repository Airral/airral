// libs/shared-types/src/lib/hiring.types.ts

export interface Job {
  id: number;
  organizationName?: string;
  organizationDomain?: string;
  organizationLogoUrl?: string;
  title: string;
  description: string;
  departmentId?: number;
  department?: string;
  hiringManagerId?: number | null;
  /** One of the company's interview kits, or none for the standard criteria. */
  interviewKitId?: number | null;
  hiringManagerName?: string | null;
  location?: string;               // e.g., "San Francisco, CA (Remote)"
  employmentType?: string;         // "Full-time", "Part-time", "Contract", "Internship"
  salaryMin?: number;              // Minimum salary
  salaryMax?: number;              // Maximum salary
  salaryCurrency?: string;         // ISO code the figures are quoted in
  salaryPeriod?: string;           // YEAR | HOUR | MONTH | WEEK | DAY | ONE_TIME; absent if the source never said
  requirements?: string;           // Required qualifications
  niceToHave?: string;             // Nice-to-have skills
  status: string | JobStatus;
  createdById?: number;  // HR user who posted (optional for backward compat)
  createdBy?: string;
  createdAt: string;
  updatedAt: string;

  // ATS Keyword System (HR sets these when posting)
  atsKeywords?: string[];           // e.g., ["Python", "5 years", "Django", "AWS"]
  atsWeights?: Record<string, number>; // Optional: keyword importance (Professional tier)
  atsMinScore?: number;            // Minimum score to show to HR (default 70%)

  // LinkedIn Integration (HR feature)
  linkedInPostId?: string;         // If auto-posted to LinkedIn
  linkedInEnabled?: boolean;       // Whether this job is posted to LinkedIn

  // Stats
  applicationCount?: number;
  atsMatchedCount?: number;        // How many matched ATS criteria
}

export const JobStatus = {
  DRAFT: 'DRAFT' as const,
  OPEN: 'OPEN' as const,
  CLOSED: 'CLOSED' as const,
  FILLED: 'FILLED' as const,
};

export type JobStatus = (typeof JobStatus)[keyof typeof JobStatus];

export interface Application {
  id: number;
  jobId: number;
  /** Whether the applicant's resume is attached, for the company to open. */
  resumeOnFile?: boolean;
  jobTitle?: string;               // Denormalized job title
  job?: Job;
  applicantId?: number;            // Applicant user ID (if registered)
  applicantName: string;
  applicantEmail: string;
  applicantPhone?: string;
  resumeUrl: string;
  resumeText?: string;             // Extracted text for ATS matching
  coverLetter?: string;
  status: ApplicationStatus;
  appliedAt: string;
  submittedAt?: string;            // Alias for appliedAt (backward compat)
  updatedAt: string;

  // ATS Scoring (calculated when application submitted)
  atsScore: number;                // 0-100%
  atsMatchedKeywords: string[];    // Which keywords matched
  atsMissingKeywords: string[];    // Which keywords didn't match
  atsMatchDetails?: Record<string, boolean>; // Detailed match per keyword

  // HR visibility control
  visibleToHR: boolean;            // false if score < atsMinScore
  reviewedByHRAt?: string;         // When HR first viewed
  reviewedByHRId?: number;

  // Review notes
  notes?: ApplicationNote[];
  activities?: ApplicationActivity[];
}

export const ApplicationStatus = {
  SUBMITTED: 'SUBMITTED' as const,
  UNDER_REVIEW: 'UNDER_REVIEW' as const,
  SHORTLISTED: 'SHORTLISTED' as const,
  INTERVIEW_SCHEDULED: 'INTERVIEW_SCHEDULED' as const,
  INTERVIEWED: 'INTERVIEWED' as const,
  OFFER_EXTENDED: 'OFFER_EXTENDED' as const,
  HIRED: 'HIRED' as const,
  REJECTED: 'REJECTED' as const,
  WITHDRAWN: 'WITHDRAWN' as const,
};

export type ApplicationStatus =
  (typeof ApplicationStatus)[keyof typeof ApplicationStatus];

export interface ApplicationNote {
  id: number;
  applicationId: number;
  content: string;
  authorId: number;
  authorName: string;
  createdAt: string;
}

export interface ApplicationActivity {
  id: number;
  applicationId: number;
  action: string;              // e.g., "Status changed to INTERVIEW"
  performedById: number;
  performedByName: string;
  details?: string;
  createdAt: string;
}

// For creating/updating jobs
export interface CreateJobRequest {
  title: string;
  description: string;
  departmentId?: number;
  department?: string;
  hiringManagerId?: number | null;
  /** One of the company's interview kits, or none for the standard criteria. */
  interviewKitId?: number | null;
  location?: string;
  employmentType?: string;
  salaryMin?: number;
  salaryMax?: number;
  requirements?: string;
  niceToHave?: string;
  status?: string;
  atsKeywords?: string[];
  atsWeights?: Record<string, number>;
  atsMinScore?: number;
  linkedInEnabled?: boolean;
}

export interface UpdateJobRequest extends Partial<CreateJobRequest> {
  status?: string | JobStatus;
}

// For submitting applications (applicant-side)
/** Where an application stands, as the applicant sees it. */
export type ApplicantStage =
  | 'APPLIED'
  | 'IN_REVIEW'
  | 'INTERVIEWING'
  | 'OFFER'
  | 'HIRED'
  | 'NOT_SELECTED'
  | 'WITHDRAWN';

/** One of the signed-in applicant's own applications on AIRRAL. */
export interface MyApplication {
  id: number;
  jobId: number;
  jobTitle: string;
  companyName?: string;
  stage: ApplicantStage;
  appliedAt: string;
  updatedAt?: string;
}

export interface SubmitApplicationRequest {
  jobId: number;
  applicantName: string;
  applicantEmail: string;
  applicantPhone?: string;
  coverLetter?: string;
  /** Only for a candidate HR adds by hand. An applicant's own resume is attached from their profile. */
  resumeUrl?: string;
}

// For HR viewing applicants with ATS filters
export interface ApplicationListFilters {
  jobId?: number;
  status?: ApplicationStatus;
  minAtsScore?: number;          // Filter by ATS score
  showBelowThreshold?: boolean;  // HR can toggle to see all
  searchTerm?: string;
}

// ATS scoring result (from backend)
export interface ATSScoreResult {
  score: number;                          // 0-100
  matchedKeywords: string[];
  missingKeywords: string[];
  matchDetails: Record<string, boolean>;
  visibleToHR: boolean;
}

// HR Encounter - Post-interaction notes and timeline for candidates
export interface HrEncounter {
  id: number;
  organizationId: number;
  encounterType: string;
  title: string;
  description?: string;
  notes?: string;
  applicationId: number;
  candidateId?: number;
  candidateName?: string;
  jobId?: number;
  jobTitle?: string;
  performedById?: number;
  performedByName?: string;
  interviewId?: number;
  offerId?: number;
  outcome?: string;
  rating?: number;
  recommendation?: string;
  priority?: string;
  encounteredAt: string;
  createdAt: string;
  metadata?: string;
}

export interface CreateEncounterRequest {
  encounterType: string;
  title: string;
  applicationId: number;
  description?: string;
  notes?: string;
  jobId?: number;
  candidateId?: number;
  interviewId?: number;
  offerId?: number;
  outcome?: string;
  rating?: number;
  recommendation?: string;
  priority?: string;
  metadata?: string;
}

/** A teammate on an interview. */
export interface InterviewerSummary {
  id: number;
  name: string;
}

export interface Interview {
  id: number;
  applicationId: number;
  jobId?: number;
  candidateName?: string;
  candidateEmail?: string;
  jobTitle?: string;
  /** Wall-clock time in timeZone, when there is one. See wallTimeToDate. */
  interviewDate: string;
  durationMinutes?: number;
  timeZone?: string;
  interviewers?: InterviewerSummary[];
  scheduledBy?: string;
  notes?: string;
  /** On My interviews only: the viewer's own scorecard, DRAFT or SUBMITTED, or absent before they start one. */
  myScorecardStatus?: 'DRAFT' | 'SUBMITTED' | null;
  interviewType?: string;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
  feedback?: string;
  rating?: number;
  scheduledByUserId?: number;
  scheduledByName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface KitQuestion {
  text: string;
  category?: string | null;
}

/** Something interviewers rate a candidate on; weight 1 to 3 is how much it counts. */
export interface KitCriterion {
  name: string;
  category?: string | null;
  weight: number;
}

export interface InterviewKit {
  id: number;
  name: string;
  description?: string | null;
  durationMinutes: number;
  questions: KitQuestion[];
  /** Empty means the standard criteria. */
  criteria: KitCriterion[];
  updatedAt?: string;
}

export interface InterviewKitRequest {
  name: string;
  description?: string;
  durationMinutes?: number;
  questions: KitQuestion[];
  criteria: KitCriterion[];
}

export type Recommendation = 'STRONG_HIRE' | 'HIRE' | 'NO_HIRE' | 'STRONG_NO_HIRE';

export interface ScoreRating {
  criterion: string;
  category?: string | null;
  weight?: number | null;
  /** 1 to 5, or null before it is rated. */
  rating?: number | null;
  notes?: string | null;
}

/** One interviewer's scorecard, with what it is about. */
export interface Scorecard {
  id?: number | null;
  interviewId: number;
  applicationId: number;
  interviewerId: number;
  interviewerName: string;
  candidateName?: string;
  jobTitle?: string;
  interviewDate?: string;
  durationMinutes?: number;
  timeZone?: string | null;
  kitName?: string | null;
  questions: KitQuestion[];
  ratings: ScoreRating[];
  overallNotes?: string | null;
  recommendation?: Recommendation | null;
  status: 'DRAFT' | 'SUBMITTED';
  submittedAt?: string | null;
  weightedScore?: number | null;
}

export interface ScorecardRequest {
  ratings: { criterion: string; rating?: number | null; notes?: string | null }[];
  overallNotes?: string;
  recommendation?: Recommendation | null;
  submit: boolean;
}

export interface ScheduleInterviewRequest {
  applicationId: number;
  /** Wall-clock time in timeZone. */
  interviewDate: string;
  notes?: string;
  /** Email the candidate the day and time. */
  notifyCandidate?: boolean;
  /** Email each interviewer an invitation with a calendar file. */
  notifyInterviewers?: boolean;
  interviewerIds?: number[];
  durationMinutes?: number;
  /** The booker's IANA time zone. */
  timeZone?: string;
}

export interface ActivityFeedItem {
  id: number;
  organizationId: number;
  activityType: string;              // e.g., 'JOB_POSTED', 'APPLICATION_SUBMITTED', 'OFFER_EXTENDED'
  category: string;                  // e.g., 'HIRING', 'TEAM', 'ANNOUNCEMENTS'
  title: string;
  description?: string;
  relatedEntityType?: string;        // e.g., 'JOB', 'APPLICATION', 'OFFER'
  relatedEntityId?: number;
  performedByUserId: number;
  performedByName: string;
  visibility: 'PUBLIC' | 'AUTHENTICATED' | 'RESTRICTED';
  createdAt: string;
}
