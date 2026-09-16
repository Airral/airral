// libs/shared-auth/src/lib/auth.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';
import { SessionEndReason } from './session-expiry';
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

/**
 * Send someone to sign in, saying why if we know.
 *
 * <p>The reason is carried so the login page can tell "your session ran out"
 * apart from "we could not confirm your session". The second is what every
 * session stored before the expiry change reads as, and calling that expired
 * would be a small invention in the one place this change exists to stop
 * inventing.
 */
function redirectToLogin(portal: PortalId | null, reason: SessionEndReason | null): false {
  const suffix = reason ? `&reason=${reason}` : '';
  const elsewhere = loginUrlFor(portal);
  window.location.href = elsewhere ?? `/login?returnUrl=${currentPathAsReturnUrl()}${suffix}`;
  return false;
}

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);
  const portal = inject(PORTAL_ID, { optional: true });

  if (consumeLocalAuthHandoff(authService)) {
    return true;
  }

  // Not "valid" -- not known to have ended. The server also rejects a token
  // inside its exp once tokenVersion moves on, which is invisible here, so
  // passing this is never permission; the interceptor's 401 handling is.
  if (authService.isAuthenticated() && tokenService.hasUnexpiredSession()) {
    return true;
  }

  // AuthService may already have cleared the session during startup, in which
  // case it holds the reason and there is nothing left to read off the token.
  // Read, never consumed: this guard can run more than once for a single
  // navigation, and the first run consuming the reason left the second run's
  // redirect to win with no explanation attached.
  const reason = authService.sessionEndReason() ?? tokenService.sessionEndReason();

  if (authService.isAuthenticated()) {
    console.warn(`Session not usable (${reason ?? 'unknown'}) - logging out`);
    authService.logout();
  }

  return redirectToLogin(portal, reason);
};
