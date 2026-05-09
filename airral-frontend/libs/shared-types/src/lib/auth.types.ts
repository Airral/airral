// libs/shared-types/src/lib/auth.types.ts

export interface User {
  id: number;
  email: string;
  firstName?: string;
  lastName?: string;
  phone?: string;

  // Organization & role
  organizationId?: number;
  organizationName?: string;
  organizationTier?: string;
  role?: string;
  roles: string[];
  isPlatformAdmin?: boolean;
  isActive: boolean;

  // Auth status
  emailVerified?: boolean;
  invitationToken?: string;
  invitationExpiresAt?: string;

  // Org structure
  managerId?: number;
  departmentId?: number;
  department?: string;
  jobTitle?: string;

  // Audit
  createdById?: number;
  createdAt?: string;
  updatedAt?: string;
  lastLoginAt?: string;
}

export interface AuthResponse {
  token: string;
  refreshToken?: string;
  userId?: number;
  email?: string;
  userEmail?: string; // legacy compatibility
  firstName?: string;
  lastName?: string;
  role?: string;
  organizationId?: number;
  organizationName?: string;
  organizationTier?: string;
  isPlatformAdmin?: boolean;
  emailVerified?: boolean;
  message: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  // User credentials
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
  phone?: string;

  // Organization basic info
  companyName?: string;
  companyDomain?: string;
  organizationTier?: string;

  // Organization contact
  primaryContactEmail?: string;
  primaryContactPhone?: string;
  billingEmail?: string;

  // Organization profile
  timezone?: string;
  country?: string;
  companySizeRange?: string;
  industry?: string;
  logoUrl?: string;

  // Branding
  brandPrimaryColor?: string;
  brandSecondaryColor?: string;

  // Location arrays
  hiringRegions?: string[];
  offices?: string[];
  departmentStructure?: string[];

  // Subscription & settings
  subscriptionPlanPreference?: string;
  compliancePreferences?: Record<string, any>;
  customSettings?: Record<string, any>;

  // Limits (if provided, limits max applications/jobs/users)
  maxUsers?: number;
  maxJobs?: number;
  maxApplicationsPerMonth?: number;

  // Invitation flow
  invitationToken?: string;
}

export interface TokenPayload {
  userId: string;
  email: string;
  role: string;
  iat: number;
  exp: number;
}
