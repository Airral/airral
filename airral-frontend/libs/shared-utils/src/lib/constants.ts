// libs/shared-utils/src/lib/constants.ts

/**
 * Detect if we're running in production based on the hostname
 */
const isProduction = typeof window !== 'undefined' &&
  (window.location.hostname.includes('airral.com') ||
   window.location.hostname === 'www.airral.com');

/**
 * API Base URL
 * Development: http://localhost:8080/api
 * Production:  https://api.airral.com
 */
export const API_BASE_URL = isProduction
  ? 'https://api.airral.com'
  : 'http://localhost:8080/api';

/**
 * Portal Routes - Cross-portal navigation URLs
 * Development: localhost with different ports
 * Production:  airral.com subdomains
 */
export const PORTAL_ROUTES = {
  WEBSITE: isProduction ? 'https://www.airral.com' : 'http://localhost:4200',
  APPLICANT: isProduction ? 'https://apply.airral.com' : 'http://localhost:4202',
  HR: isProduction ? 'https://app.airral.com' : 'http://localhost:4201',
  ADMIN: isProduction ? 'https://admin.airral.com' : 'http://localhost:4203'
};

export const USER_ROLES = {
  ADMIN: 'ADMIN',
  HR_MANAGER: 'HR_MANAGER',
  MANAGER: 'MANAGER',
  EMPLOYEE: 'EMPLOYEE',
  APPLICANT: 'APPLICANT'
};

export const JOB_STATUS = {
  DRAFT: 'DRAFT',
  OPEN: 'OPEN',
  CLOSED: 'CLOSED'
};

export const APPLICATION_STATUS = {
  SUBMITTED: 'SUBMITTED',
  UNDER_REVIEW: 'UNDER_REVIEW',
  SHORTLISTED: 'SHORTLISTED',
  REJECTED: 'REJECTED',
  ACCEPTED: 'ACCEPTED'
};
