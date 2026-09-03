// libs/shared-auth/src/lib/auth.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { consumeLocalAuthHandoff } from './auth-handoff';
import { PORTAL_ID, PortalId } from './portal-id';

/**
 * Where an unauthenticated visitor to this app belongs.
 *
 * Returning null means "this app serves its own /login", which is the only
 * case where a returnUrl survives, since it is a same-origin path.
 *
 * The admin portal has no /login of its own. It used to fall through to the
 * marketing site, whose /login shim forwards to the applicant portal -- so an
 * administrator opening the admin portal was asked to "continue your job
 * search". Administrators are HR-side users; USER_ROLES.ADMIN is accepted by
 * the HR login, so that is where they go.
 */
function loginUrlFor(portal: PortalId | null): string | null {
  switch (portal) {
    case 'hr':
    case 'applicant':
      return null;
    case 'admin':
      return `${PORTAL_ROUTES.HR}/login`;
    default:
      return `${PORTAL_ROUTES.WEBSITE}/login`;
  }
}

function currentPathAsReturnUrl(): string {
  return encodeURIComponent(
    `${window.location.pathname}${window.location.search}${window.location.hash}`
  );
}

function redirectToLogin(portal: PortalId | null): false {
  const elsewhere = loginUrlFor(portal);
  window.location.href = elsewhere ?? `/login?returnUrl=${currentPathAsReturnUrl()}`;
  return false;
}

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);
  const portal = inject(PORTAL_ID, { optional: true });

  if (consumeLocalAuthHandoff(authService)) {
    return true;
  }

  // Check if user is authenticated AND token is not expired
  if (authService.isAuthenticated() && tokenService.isTokenValid()) {
    return true;
  }

  // Token expired or invalid - logout and redirect
  if (authService.isAuthenticated()) {
    console.warn('Token expired - logging out');
    authService.logout();
  }

  return redirectToLogin(portal);
};
