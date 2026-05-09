// libs/shared-types/src/lib/job.types.ts

// Note: Job, CreateJobRequest, and ApplicationStatus are now exported from hiring.types.ts
// This file contains ATS scoring helper types only

// ATS Scoring for applicants
export interface ATSScore {
  applicantId: number;
  jobId: number;
  matchPercentage: number;              // 0-100%
  matchedKeywords: string[];            // Keywords found in resume
  missedKeywords: string[];             // Keywords not found
  scoredAt: Date;
  isAboveThreshold: boolean;            // >= 70% or custom threshold
}

export interface ATSKeywordMatch {
  keyword: string;
  found: boolean;
  weight?: number;                      // Professional tier: weighted scoring
  locations?: string[];                 // Where keyword was found (skills, experience, etc.)
}
