import { InjectionToken } from '@angular/core';

export type PortalId = 'website' | 'applicant' | 'hr' | 'admin';

/**
 * Which app this bundle is. Provided by each app at bootstrap.
 *
 * Deliberately not derived from window.location. The guards used to answer
 * "am I the applicant portal?" by comparing window.location.origin against a
 * URL supplied at deploy time, which made identity depend on two independently
 * configured strings matching character for character. A trailing slash, a www,
 * or a preview host was enough to make every guarded route redirect off-site,
 * and nothing logged when it did.
 *
 * An app knows what it is at build time. It should not have to infer it.
 */
export const PORTAL_ID = new InjectionToken<PortalId>('PORTAL_ID');

/**
 * A post-login destination that cannot leave this origin.
 *
 * Only same-origin absolute paths are honoured. A protocol-relative value like
 * "//example.com" starts with "/" but is a different site, so it is rejected;
 * so is a returnUrl pointing back at /login, which would loop.
 */
export function safeReturnUrl(candidate: string | null | undefined, fallback = '/'): string {
  if (!candidate) {
    return fallback;
  }
  if (!candidate.startsWith('/') || candidate.startsWith('//')) {
    return fallback;
  }
  if (candidate.startsWith('/login')) {
    return fallback;
  }
  return candidate;
}
