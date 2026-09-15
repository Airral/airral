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
 * Routes that stay reachable whatever state a user's preferences are in.
 *
 * <p>/profile is the only page where preferences can be read, changed or
 * cleared, and it was the one page this guard locked out of exactly the
 * situation that needs it. Onboarding persists nothing until the end of step 3,
 * so anyone who started the flow and stopped had no targetRoles, and every
 * attempt to open /profile bounced back to /onboarding -- no route to their own
 * settings, and nothing on the page explaining why. Clearing targetRoles on
 * purpose, which is now a supported action, produced the same dead end.
 *
 * <p>Persisting each onboarding step would not fix this on its own: the guard's
 * test is "has target roles", and a deliberately empty role list fails it no
 * matter how it got saved. So the route exemption is the necessary half of the
 * fix, and it is the smaller one -- it touches nothing about how onboarding
 * saves, and it leaves the redirect in place everywhere else.
 */
const ALWAYS_REACHABLE_PATHS = ['/profile'];

function isAlwaysReachable(url: string): boolean {
  const path = (url || '').split(/[?#]/)[0];
  return ALWAYS_REACHABLE_PATHS.some((allowed) => path === allowed || path.startsWith(`${allowed}/`));
}

/**
 * Sends a user who has never set anything up to /onboarding. Runs on the main
 * app routes and, deliberately, on the public /jobs route.
 */
export const onboardingGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const candidateApi = inject(CandidatePortalService);
  const router = inject(Router);

  const user = auth.getCurrentUser();
  if (!user?.email) {
    return true;
  }

  if (isAlwaysReachable(state.url)) {
    return true;
  }

  if (isUserOnboarded(user.email)) {
    return true;
  }

  return candidateApi.getCandidateProfile(user.email).pipe(
    map((profile) => {
      // The test used to be "has target roles", which cannot tell a new user
      // from someone who cleared theirs. A profile that has never been through
      // the setup form comes back with no matchPreferences object at all -- the
      // backend stores "{}" for a freshly created profile and maps that to null
      // on the way out -- and that is what a genuinely new user looks like.
      // Someone who saved the form and left the role box empty has already been
      // shown the question and is not helped by being sent back to it.
      if (profile.matchPreferences == null) {
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
