// libs/shared-auth/src/lib/role.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { consumeLocalAuthHandoff } from './auth-handoff';
import { PORTAL_ID, PortalId } from './portal-id';

function isLocalDevHost(): boolean {
  return (
    window.location.hostname === 'localhost' ||
    window.location.hostname === '127.0.0.1' ||
    window.location.hostname === '0.0.0.0'
  );
}

/** See auth.guard.ts for why each app resolves its login this way. */
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

function redirectToLogin(portal: PortalId | null): false {
  const elsewhere = loginUrlFor(portal);
  const returnUrl = encodeURIComponent(
    `${window.location.pathname}${window.location.search}${window.location.hash}`
  );
  window.location.href = elsewhere ?? `/login?returnUrl=${returnUrl}`;
  return false;
}

export const roleGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const portal = inject(PORTAL_ID, { optional: true });
  const requiredRoles = (route.data?.['roles'] as string[] | undefined) ?? [];

  consumeLocalAuthHandoff(authService);

  if (!authService.isAuthenticated()) {
    return redirectToLogin(portal);
  }

  if (requiredRoles.length === 0) {
    return true;
  }

  const normalizedRoles = requiredRoles.map((role) => role.toUpperCase());

  // Map HR segment access to both ADMIN and HR_MANAGER backend roles
  if (normalizedRoles.includes('HR')) {
    normalizedRoles.push('ADMIN', 'HR_MANAGER');
    normalizedRoles.splice(normalizedRoles.indexOf('HR'), 1); // Remove 'HR'
  }

  if (authService.hasAnyRole(...normalizedRoles)) {
    return true;
  }

  // On HR portal in dev, redirect to home instead of external website
  if (portal === 'hr' && isLocalDevHost()) {
    window.location.href = '/';
    return false;
  }

  window.location.href = PORTAL_ROUTES.WEBSITE;
  return false;
};
