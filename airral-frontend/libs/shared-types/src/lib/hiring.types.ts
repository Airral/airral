// libs/shared-types/src/lib/hiring.types.ts

export interface Job {
  id: number;
  title: string;
  description: string;
  departmentId?: number;
  department?: string;
  location?: string;               // e.g., "San Francisco, CA (Remote)"
  employmentType?: string;         // "Full-time", "Part-time", "Contract", "Internship"
  salaryMin?: number;              // Minimum salary
  salaryMax?: number;              // Maximum salary
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
export interface SubmitApplicationRequest {
  jobId: number;
  coverLetter: string;
  resumeUrl: string;
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
  applicationId: number;
  candidateId: number;
  candidateName: string;
  candidateEmail: string;
  jobTitle?: string;
  encounterType: 'PHONE_SCREENING' | 'TECHNICAL_INTERVIEW' | 'BEHAVIORAL_INTERVIEW' | 'FINAL_ROUND' | 'FEEDBACK_SESSION' | 'OFFER_DISCUSSION' | 'OTHER';
  encounteredByUserId: number;
  encounteredByName: string;
  encounteredAt: string;
  summary?: string;                      // Notes from the interaction
  outcome?: string;                      // Result of the encounter
  followUpRequired: boolean;
  followUpDate?: string;
  attachments?: string[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateEncounterRequest {
  applicationId: number;
  candidateId: number;
  encounterType: string;
  summary?: string;
  outcome?: string;
  followUpRequired?: boolean;
  followUpDate?: string;
}

export interface Interview {
  id: number;
  applicationId: number;
  candidateName?: string;
  candidateEmail?: string;
  jobTitle?: string;
  interviewDate: string;
  interviewType?: string;
  status: 'SCHEDULED' | 'COMPLETED' | 'CANCELLED';
  feedback?: string;
  rating?: number;
  scheduledByUserId?: number;
  scheduledByName?: string;
  createdAt: string;
  updatedAt: string;
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
