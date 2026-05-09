// libs/shared-types/src/lib/employee.types.ts

// Yearly Reviews (Manager creates, Employee views)
export interface YearlyReview {
  id: number;
  employeeId: number;
  employeeName: string;
  managerId: number;
  managerName: string;
  reviewPeriodStart: string;   // e.g., "2025-01-01"
  reviewPeriodEnd: string;     // e.g., "2025-12-31"
  status: ReviewStatus;
  createdAt: string;
  submittedAt?: string;        // When manager submitted
  acknowledgedAt?: string;     // When employee acknowledged

  // Review content
  overallRating?: number;      // 1-5 scale
  strengths?: string;
  areasForImprovement?: string;
  goals?: string;
  managerComments?: string;
  employeeComments?: string;   // Employee can add comments

  // Competencies/Skills assessment
  competencies?: ReviewCompetency[];
}

export type ReviewStatus =
  | 'DRAFT'         // Manager is writing
  | 'SUBMITTED'     // Manager submitted, employee can view
  | 'ACKNOWLEDGED'  // Employee has seen it
  | 'ARCHIVED';     // Past review, archived

export interface ReviewCompetency {
  name: string;              // e.g., "Communication", "Leadership"
  rating: number;            // 1-5
  comments?: string;
}

export interface CreateReviewRequest {
  employeeId: number;
  reviewPeriodStart: string;
  reviewPeriodEnd: string;
  overallRating?: number;
  strengths?: string;
  areasForImprovement?: string;
  goals?: string;
  managerComments?: string;
  competencies?: ReviewCompetency[];
}

// Referrals (Employee/Manager submits, HR reviews)
export interface Referral {
  id: number;
  referredById: number;       // Employee/Manager who referred
  referredByName: string;
  referredName: string;
  referredEmail: string;
  referredPhone?: string;
  jobId?: number;             // Which job they're referred for
  jobTitle?: string;
  status: ReferralStatus;
  submittedAt: string;
  reviewedAt?: string;
  reviewedById?: number;      // HR user who reviewed

  // Referral details
  resumeUrl?: string;
  notes?: string;             // Why they're a good fit
  relationship?: string;      // How referrer knows them

  // If converted to application
  applicationId?: number;

  // Referral bonus tracking (if applicable)
  bonusAmount?: number;
  bonusPaidAt?: string;
}

export type ReferralStatus =
  | 'PENDING'        // HR hasn't reviewed yet
  | 'REVIEWING'      // HR is reviewing
  | 'CONTACTED'      // HR reached out to candidate
  | 'APPLIED'        // Converted to application
  | 'HIRED'          // Successfully hired!
  | 'DECLINED';      // HR declined the referral

export interface CreateReferralRequest {
  referredName: string;
  referredEmail: string;
  referredPhone?: string;
  jobId?: number;
  notes?: string;
  relationship?: string;
  resumeUrl?: string;
}

// Benefits (Employee/Manager view, HR manages)
export interface EmployeeBenefits {
  employeeId: number;
  employeeName: string;

  // Health & Wellness
  healthInsurance?: BenefitItem;
  dentalInsurance?: BenefitItem;
  visionInsurance?: BenefitItem;

  // Financial
  salary?: number;
  bonus?: number;
  equity?: EquityDetails;
  retirement401k?: BenefitItem;

  // Time Off
  ptoBalance?: number;        // Days available
  ptoUsed?: number;
  ptoTotal?: number;
  sickDays?: number;

  // Other
  otherBenefits?: BenefitItem[];
}

export interface BenefitItem {
  name: string;
  description?: string;
  provider?: string;
  cost?: number;              // Employee contribution
  employerCost?: number;
  effectiveDate?: string;
}

export interface EquityDetails {
  shares?: number;
  vestingSchedule?: string;
  cliffDate?: string;
  exercisePrice?: number;
}

// Payroll (Enterprise tier only, HR manages)
export interface PayrollRun {
  id: number;
  periodStart: string;
  periodEnd: string;
  payDate: string;
  status: 'DRAFT' | 'PROCESSING' | 'PAID' | 'FAILED';
  totalAmount: number;
  employeeCount: number;
  createdById: number;
  createdAt: string;
}

export interface PayrollEntry {
  id: number;
  payrollRunId: number;
  employeeId: number;
  employeeName: string;
  grossPay: number;
  deductions: number;
  netPay: number;
  hours?: number;
  overtime?: number;
}
