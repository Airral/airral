import { Router } from '@angular/router';
import { User } from '@airral/shared-types';
import { PORTAL_ROUTES, USER_ROLES } from '@airral/shared-utils';
import { buildLocalAuthHandoffUrl } from './auth-handoff';
import { PortalId, safeReturnUrl } from './portal-id';

/**
 * Which app should serve a user holding this role.
 *
 * Both login pages accept any valid credentials -- /api/auth/login returns the
 * role, so neither page needs the visitor to declare which kind of user they
 * are. Whoever arrives at the wrong door is forwarded rather than refused: the
 * applicant login used to answer an employer with "This login is for
 * applicants", offering no link onward, which dead-ended anyone who took the
 * website's Sign in button while holding an employer account.
 *
 * ADMIN resolves to the HR portal. The admin portal is a separate surface for
 * platform statistics and is not where an administrator signs in -- it has no
 * login of its own.
 */
export function portalForRole(role: string | null | undefined): PortalId {
  switch ((role ?? '').toUpperCase()) {
    case USER_ROLES.ADMIN:
    case USER_ROLES.HR_MANAGER:
    case USER_ROLES.MANAGER:
    case USER_ROLES.EMPLOYEE:
      return 'hr';
    default:
      // Includes APPLICANT and anything unrecognised. Defaulting to the public
      // audience is the safe side to err on: it grants no employer surface.
      return 'applicant';
  }
}

const PORTAL_URLS: Record<PortalId, string> = {
  website: PORTAL_ROUTES.WEBSITE,
  applicant: PORTAL_ROUTES.APPLICANT,
  hr: PORTAL_ROUTES.HR,
  admin: PORTAL_ROUTES.ADMIN,
};

/**
 * Send a freshly authenticated user where they belong.
 *
 * Same origin is an in-place router navigation, which is what preserves the
 * returnUrl a guard recorded. A different origin needs the session to travel
 * in the URL, and a returnUrl cannot come along -- it is a path on the origin
 * being left behind.
 */
export function routeAfterAuth(opts: {
  role: string | null | undefined;
  currentPortal: PortalId | null;
  user: User;
  token: string;
  router: Router;
  returnUrl?: string | null;
  /** Overrides returnUrl when staying put, e.g. '/onboarding' after signup. */
  sameOriginDefault?: string;
}): void {
  const target = portalForRole(opts.role);

  if (target === opts.currentPortal) {
    opts.router.navigateByUrl(
      opts.sameOriginDefault ?? safeReturnUrl(opts.returnUrl)
    );
    return;
  }

  window.location.href = buildLocalAuthHandoffUrl(
    PORTAL_URLS[target],
    opts.user,
    opts.token
  );
}
