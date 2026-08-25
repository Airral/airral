import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '@airral/shared-auth';
import { CandidatePortalService } from '@airral/shared-api';
import { map, catchError } from 'rxjs/operators';
import { of } from 'rxjs';

const LEGACY_ONBOARDED_KEY = 'airral_onboarded';
const USER_ONBOARDED_PREFIX = 'airral_onboarded:';

export function markUserOnboarded(email: string | undefined): void {
  if (!email) {
    return;
  }

  try {
    localStorage.setItem(getUserOnboardedKey(email), 'true');
    localStorage.removeItem(LEGACY_ONBOARDED_KEY);
  } catch {
    // localStorage unavailable
  }
}

function isUserOnboarded(email: string | undefined): boolean {
  if (!email) {
    return false;
  }

  try {
    localStorage.removeItem(LEGACY_ONBOARDED_KEY);
    return localStorage.getItem(getUserOnboardedKey(email)) === 'true';
  } catch {
    return false;
  }
}

function getUserOnboardedKey(email: string): string {
  return `${USER_ONBOARDED_PREFIX}${email.trim().toLowerCase()}`;
}

/**
 * Redirects users to /onboarding if their profile has no targetRoles set
 * (i.e. they haven't completed onboarding). Runs on the main app routes.
 */
export const onboardingGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const candidateApi = inject(CandidatePortalService);
  const router = inject(Router);

  const user = auth.getCurrentUser();
  if (!user?.email) {
    return true;
  }

  if (isUserOnboarded(user.email)) {
    return true;
  }

  return candidateApi.getCandidateProfile(user.email).pipe(
    map((profile) => {
      const hasTargetRoles =
        profile.matchPreferences?.targetRoles &&
        profile.matchPreferences.targetRoles.length > 0;

      if (!hasTargetRoles) {
        return router.createUrlTree(['/onboarding']);
      }

      markUserOnboarded(user.email);
      return true;
    }),
    catchError(() => of(true))
  );
};

/**
 * Prevents already-onboarded users from seeing the onboarding page again.
 * Does NOT re-fetch profile — uses a per-user localStorage flag set after onboarding completes.
 */
export const onboardingPageGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const user = auth.getCurrentUser();

  if (isUserOnboarded(user?.email)) {
    return router.createUrlTree(['/jobs']);
  }

  return true;
};
