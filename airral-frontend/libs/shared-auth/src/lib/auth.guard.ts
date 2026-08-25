// libs/shared-auth/src/lib/auth.guard.ts
import { CanActivateFn } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { consumeLocalAuthHandoff } from './auth-handoff';

function isLocalDevHost(): boolean {
  return (
    window.location.hostname === 'localhost' ||
    window.location.hostname === '127.0.0.1' ||
    window.location.hostname === '0.0.0.0'
  );
}

function isLocalHrPortal(): boolean {
  return isLocalDevHost() && window.location.port === '4202';
}

function isApplicantPortalHost(): boolean {
  return window.location.origin === PORTAL_ROUTES.APPLICANT || (isLocalDevHost() && window.location.port === '4201');
}

function isHrPortalHost(): boolean {
  return window.location.origin === PORTAL_ROUTES.HR || isLocalHrPortal();
}

function redirectToLogin(): false {
  if (isHrPortalHost() || isApplicantPortalHost()) {
    const returnUrl = encodeURIComponent(`${window.location.pathname}${window.location.search}${window.location.hash}`);
    window.location.href = `/login?returnUrl=${returnUrl}`;
    return false;
  }

  window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  return false;
}

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);

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

  return redirectToLogin();
};
