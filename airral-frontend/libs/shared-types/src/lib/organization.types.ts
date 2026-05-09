// libs/shared-types/src/lib/organization.types.ts

export enum OrganizationTier {
  QUICK_HIRE = 'QUICK_HIRE',
  PROFESSIONAL = 'PROFESSIONAL',
  ENTERPRISE = 'ENTERPRISE'
}

export interface Organization {
  id: number;
  name: string;
  tier: OrganizationTier;
  employeeCount?: number;
  activeJobs?: number;
  monthlyApplications?: number;
  createdAt: Date;
  settings?: OrganizationSettings;
}

export interface OrganizationSettings {
  timezone: string;
  defaultLanguage: string;
  emailNotifications: boolean;
  features: FeatureFlags;
  integrations?: IntegrationSettings;
}

export interface IntegrationSettings {
  linkedIn?: LinkedInIntegration;
  indeed?: IndeedIntegration;
  // Add more integrations as needed
}

export interface LinkedInIntegration {
  connected: boolean;
  companyPageId?: string;
  accessToken?: string;
  tokenExpiresAt?: string;
  autoPostJobs: boolean;          // Auto-post all new jobs to LinkedIn
  connectedAt?: string;
  connectedByUserId?: number;
}

export interface IndeedIntegration {
  connected: boolean;
  employerId?: string;
  autoPostJobs: boolean;
}

export interface FeatureFlags {
  // Core Features (always available in Quick Hire)
  jobPosting: boolean;
  applicationReview: boolean;
  basicInterviews: boolean;

  // Record-Keeping (Essential for Quick Hire)
  applicationNotes: boolean;          // Add notes/comments to candidates
  interviewFeedback: boolean;         // Capture interview notes & ratings
  activityLog: boolean;               // Timeline of who did what
  rejectionReasons: boolean;          // Track why candidates were rejected
  statusHistory: boolean;             // See status change history

  // Communication (Essential for Quick Hire)
  emailNotifications: boolean;        // Auto-notify on status changes
  emailTemplates: boolean;            // Pre-built email templates
  candidateMessaging: boolean;        // Send messages to applicants

  // File Management (Essential for Quick Hire)
  resumeStorage: boolean;             // Store & download resumes
  fileAttachments: boolean;           // Additional file uploads

  // Search & Tools (Essential for Quick Hire)
  basicSearch: boolean;               // Search by name, email, job
  statusFilters: boolean;             // Filter by application status
  csvExport: boolean;                 // Export data to CSV

  // Professional Features
  customPipelineStages: boolean;
  analytics: boolean;
  interviewScorecards: boolean;
  approvalWorkflows: boolean;
  bulkActions: boolean;
  teamView: boolean;
  offerManagement: boolean;
  referralTracking: boolean;
  candidateSourcing: boolean;
  advancedReporting: boolean;
  // Enterprise Features
  multiEntity: boolean;
  advancedAnalytics: boolean;
  integrations: boolean;
  complianceTools: boolean;
  deiReporting: boolean;
  talentPools: boolean;
  apiAccess: boolean;
}

export const TIER_FEATURE_FLAGS: Record<OrganizationTier, FeatureFlags> = {
  [OrganizationTier.QUICK_HIRE]: {
    // Core Hiring
    jobPosting: true,
    applicationReview: true,
    basicInterviews: true,

    // Record-Keeping (Essential for compliance & knowledge retention)
    applicationNotes: true,
    interviewFeedback: true,
    activityLog: true,
    rejectionReasons: true,
    statusHistory: true,

    // Communication (Essential for candidate experience)
    emailNotifications: true,
    emailTemplates: true,
    candidateMessaging: true,

    // File Management (Can't hire without resumes!)
    resumeStorage: true,
    fileAttachments: true,

    // Search & Tools (Essential as candidate volume grows)
    basicSearch: true,
    statusFilters: true,
    csvExport: true,

    // Professional Features (Not included)
    customPipelineStages: false,
    analytics: false,
    interviewScorecards: false,
    approvalWorkflows: false,
    bulkActions: false,
    teamView: false,
    offerManagement: false,
    referralTracking: false,
    candidateSourcing: false,
    advancedReporting: false,

    // Enterprise Features (Not included)
    multiEntity: false,
    advancedAnalytics: false,
    integrations: false,
    complianceTools: false,
    deiReporting: false,
    talentPools: false,
    apiAccess: false,
  },
  [OrganizationTier.PROFESSIONAL]: {
    // Core Hiring
    jobPosting: true,
    applicationReview: true,
    basicInterviews: true,

    // Record-Keeping
    applicationNotes: true,
    interviewFeedback: true,
    activityLog: true,
    rejectionReasons: true,
    statusHistory: true,

    // Communication
    emailNotifications: true,
    emailTemplates: true,
    candidateMessaging: true,

    // File Management
    resumeStorage: true,
    fileAttachments: true,

    // Search & Tools
    basicSearch: true,
    statusFilters: true,
    csvExport: true,

    // Professional Features (All unlocked)
    customPipelineStages: true,
    analytics: true,
    interviewScorecards: true,
    approvalWorkflows: true,
    bulkActions: true,
    teamView: true,
    offerManagement: true,
    referralTracking: true,
    candidateSourcing: true,
    advancedReporting: true,

    // Enterprise Features (Not included)
    multiEntity: false,
    advancedAnalytics: false,
    integrations: false,
    complianceTools: false,
    deiReporting: false,
    talentPools: false,
    apiAccess: false,
  },
  [OrganizationTier.ENTERPRISE]: {
    // Core Hiring
    jobPosting: true,
    applicationReview: true,
    basicInterviews: true,

    // Record-Keeping
    applicationNotes: true,
    interviewFeedback: true,
    activityLog: true,
    rejectionReasons: true,
    statusHistory: true,

    // Communication
    emailNotifications: true,
    emailTemplates: true,
    candidateMessaging: true,

    // File Management
    resumeStorage: true,
    fileAttachments: true,

    // Search & Tools
    basicSearch: true,
    statusFilters: true,
    csvExport: true,

    // Professional Features
    customPipelineStages: true,
    analytics: true,
    interviewScorecards: true,
    approvalWorkflows: true,
    bulkActions: true,
    teamView: true,
    offerManagement: true,
    referralTracking: true,
    candidateSourcing: true,
    advancedReporting: true,

    // Enterprise Features (All unlocked)
    multiEntity: true,
    advancedAnalytics: true,
    integrations: true,
    complianceTools: true,
    deiReporting: true,
    talentPools: true,
    apiAccess: true,
  },
};

export function getFeaturesForTier(tier: OrganizationTier): FeatureFlags {
  return TIER_FEATURE_FLAGS[tier];
}

export function detectRecommendedTier(
  employeeCount: number,
  openJobs: number,
  monthlyApplications: number
): OrganizationTier {
  if (employeeCount <= 10 && openJobs <= 5 && monthlyApplications < 100) {
    return OrganizationTier.QUICK_HIRE;
  }

  if (employeeCount <= 100 && openJobs <= 20) {
    return OrganizationTier.PROFESSIONAL;
  }

  return OrganizationTier.ENTERPRISE;
}
