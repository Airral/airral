// libs/shared-auth/src/lib/role.guard.ts
import { Injectable } from '@angular/core';
import { ActivatedRouteSnapshot, CanActivateFn, Router } from '@angular/router';
import { inject } from '@angular/core';
import { AuthService } from './auth.service';
import { PORTAL_ROUTES } from '@airral/shared-utils';

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

@Injectable({
  providedIn: 'root'
})
export class RoleGuard {
  constructor(private authService: AuthService, private router: Router) {}

  canActivate(route: ActivatedRouteSnapshot): boolean {
    const requiredRoles = route.data['roles'] as string[];

    if (!this.authService.isAuthenticated()) {
      window.location.href = `${PORTAL_ROUTES.WEBSITE}/login`;
      return false;
    }

    if (requiredRoles && requiredRoles.length > 0) {
      if (this.authService.hasAnyRole(...requiredRoles)) {
        return true;
      }
      window.location.href = PORTAL_ROUTES.WEBSITE;
      return false;
    }

    return true;
  }
}

export const roleGuard: CanActivateFn = (route) => {
  const authService = inject(AuthService);
  const requiredRoles = (route.data?.['roles'] as string[] | undefined) ?? [];

  if (!authService.isAuthenticated()) {
    window.location.href = isHrPortalHost() ? '/login' : `${PORTAL_ROUTES.WEBSITE}/login`;
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
