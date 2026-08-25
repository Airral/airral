// libs/shared-auth/src/lib/role.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { consumeLocalAuthHandoff } from './auth-handoff';

function isLocalDevHost(): boolean {
  return (
    window.location.hostname === 'localhost' ||
    window.location.hostname === '127.0.0.1' ||
    window.location.hostname === '0.0.0.0'
  );
}

function isHrPortalHost(): boolean {
  return window.location.origin === PORTAL_ROUTES.HR || (isLocalDevHost() && window.location.port === '4202');
}

function isApplicantPortalHost(): boolean {
  return window.location.origin === PORTAL_ROUTES.APPLICANT || (isLocalDevHost() && window.location.port === '4201');
}

function redirectToLocalLogin(): false {
  const returnUrl = encodeURIComponent(`${window.location.pathname}${window.location.search}${window.location.hash}`);
  window.location.href = `/login?returnUrl=${returnUrl}`;
  return false;
}

export const roleGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const requiredRoles = (route.data?.['roles'] as string[] | undefined) ?? [];

  consumeLocalAuthHandoff(authService);

  if (!authService.isAuthenticated()) {
    if (isHrPortalHost() || isApplicantPortalHost()) {
      return redirectToLocalLogin();
    }
    window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
    return false;
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
  if (isHrPortalHost() && isLocalDevHost()) {
    window.location.href = '/';
    return false;
  }

  window.location.href = PORTAL_ROUTES.WEBSITE;
  return false;
};
