// libs/shared-auth/src/lib/auth.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { consumeLocalAuthHandoff } from './auth-handoff';
import { PORTAL_ID, PortalId } from './portal-id';

/**
 * Whether this app serves its own /login. The admin portal does not -- it is
 * reached by handoff -- so an unauthenticated visitor there belongs on the
 * marketing site's login, not on a route that does not exist.
 */
function hasOwnLogin(portal: PortalId | null): boolean {
  return portal === 'hr' || portal === 'applicant';
}

function redirectToLogin(portal: PortalId | null): false {
  if (hasOwnLogin(portal)) {
    const returnUrl = encodeURIComponent(
      `${window.location.pathname}${window.location.search}${window.location.hash}`
    );
    window.location.href = `/login?returnUrl=${returnUrl}`;
    return false;
  }

  window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
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
