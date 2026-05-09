// libs/shared-auth/src/lib/auth.guard.ts
import { Injectable } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';

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

function isHrPortalHost(): boolean {
  return window.location.origin === PORTAL_ROUTES.HR || isLocalHrPortal();
}

function redirectToLogin(): false {
  if (isHrPortalHost()) {
    window.location.href = '/login';
    return false;
  }

  window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
  return false;
}

@Injectable({
  providedIn: 'root'
})
export class AuthGuard {
  constructor(private authService: AuthService, private router: Router) {}

  canActivate(): boolean {
    if (this.authService.isAuthenticated()) {
      return true;
    }
    return redirectToLogin();
  }
}

export const authGuard: CanActivateFn = () => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);

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
