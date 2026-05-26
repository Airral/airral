// libs/shared-utils/src/lib/constants.ts

declare const process: { env?: Record<string, string | undefined> } | undefined;

const runtimeEnv = typeof process !== 'undefined' ? process.env ?? {} : {};
const configuredApiBaseUrl = runtimeEnv['AIRRAL_API_BASE_URL'] || runtimeEnv['API_BASE_URL'];
const serverProduction =
  runtimeEnv['AIRRAL_ENV'] === 'production' ||
  runtimeEnv['NODE_ENV'] === 'production';
const browserProduction = typeof window !== 'undefined' &&
  (window.location.hostname.includes('airral.com') ||
   window.location.hostname === 'www.airral.com');
const isProduction = browserProduction || serverProduction;

const localHostname = typeof window !== 'undefined' && window.location.hostname !== '0.0.0.0'
  ? window.location.hostname
  : 'localhost';

const localPortal = (port: number) => `http://${localHostname}:${port}`;

/**
 * API Base URL
 * Development: current local hostname on port 8080
 * Production:  https://api.airral.com
 */
export const API_BASE_URL = isProduction
  ? configuredApiBaseUrl || 'https://api.airral.com'
  : `http://${localHostname}:8080/api`;

/**
 * Portal Routes - Cross-portal navigation URLs
 * Development: localhost with different ports
 * Production:  airral.com subdomains
 */
export const PORTAL_ROUTES = {
  WEBSITE: isProduction ? 'https://www.airral.com' : localPortal(4200),
  APPLICANT: isProduction ? 'https://apply.airral.com' : localPortal(4201),
  HR: isProduction ? 'https://app.airral.com' : localPortal(4202),
  ADMIN: isProduction ? 'https://admin.airral.com' : localPortal(4203)
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
