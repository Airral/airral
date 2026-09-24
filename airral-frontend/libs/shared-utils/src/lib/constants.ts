// libs/shared-utils/src/lib/constants.ts

declare const process: { env?: Record<string, string | undefined> } | undefined;

const runtimeEnv = typeof process !== 'undefined' ? process.env ?? {} : {};

// Injected by runtime-config.js, which the deploy rewrites per environment.
// Read via a cast rather than `declare global` so this does not collide with the
// Window augmentation in shared-ui's google-auth-button component.
const browserRuntimeConfig =
  (typeof window !== 'undefined'
    ? (window as unknown as {
        AIRRAL_RUNTIME_CONFIG?: {
          apiBaseUrl?: string;
          websiteUrl?: string;
          applicantUrl?: string;
          hrUrl?: string;
          adminUrl?: string;
        };
      }).AIRRAL_RUNTIME_CONFIG
    : undefined) ?? {};

const configuredApiBaseUrl =
  (browserRuntimeConfig.apiBaseUrl || '').trim() ||
  runtimeEnv['AIRRAL_API_BASE_URL'] ||
  runtimeEnv['API_BASE_URL'];
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
const hrDevPorts = ['4202', '4205'];
const localApiBaseUrl = typeof window !== 'undefined' && hrDevPorts.includes(window.location.port)
  ? '/api'
  : `http://${localHostname}:8080/api`;

/**
 * API Base URL
 * Development: current local hostname on port 8080
 * Production:  https://api.airral.com
 */
export const API_BASE_URL = configuredApiBaseUrl
  ? configuredApiBaseUrl
  : isProduction
    ? 'https://api.airral.com'
    : localApiBaseUrl;

/**
 * Cross-portal navigation URLs.
 *
 * Resolution order per portal: runtime-config.js, then the airral.com
 * subdomain in production, then localhost in development.
 *
 * These are deliberately runtime-configurable rather than compiled in. A
 * portal that has not had its subdomain pointed yet (or lives on a preview
 * host) can be reached by setting it at deploy time, instead of shipping a
 * link to a hostname that does not resolve — which silently breaks every
 * call to action on the site.
 */
const portal = (configured: string | undefined, productionUrl: string, devPort: number) => {
  const trimmed = (configured || '').trim().replace(/\/$/, '');
  if (trimmed) {
    return trimmed;
  }
  return isProduction ? productionUrl : localPortal(devPort);
};

export const PORTAL_ROUTES = {
  WEBSITE: portal(browserRuntimeConfig.websiteUrl, 'https://www.airral.com', 4200),
  APPLICANT: portal(browserRuntimeConfig.applicantUrl, 'https://apply.airral.com', 4201),
  HR: portal(browserRuntimeConfig.hrUrl, 'https://app.airral.com', 4202),
  ADMIN: portal(browserRuntimeConfig.adminUrl, 'https://admin.airral.com', 4203)
};

/**
 * The Firebase web app AIRRAL uses for one job: emailing a one-time link to an
 * address and confirming it was followed. Accounts, passwords and sessions stay
 * in AIRRAL's own database; Firebase never sees a password.
 *
 * <p>Not secrets. A Firebase web config is shipped in every page that uses it by
 * design; what protects it is that the API key only answers from AIRRAL's own
 * origins and only for the two sign-in APIs (restricted in Google Cloud), and
 * that the backend accepts only tokens minted for this project.
 */
export const FIREBASE_WEB_CONFIG = {
  apiKey: 'AIzaSyBEVR_Zk-T_XDGPKSa_IIeJB-XubcMC21w',
  authDomain: 'airral-a0e81.firebaseapp.com',
  projectId: 'airral-a0e81',
  appId: '1:965095345971:web:9a89e7f6a2fab97117f846',
} as const;

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
