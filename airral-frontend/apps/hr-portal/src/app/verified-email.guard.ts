import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '@airral/shared-auth';

/**
 * The workspace opens once the account's address is proven. A company's first
 * HR manager proves it with the link emailed at sign-up; invited teammates
 * proved theirs by accepting. Until then they see "Check your inbox", so a
 * sign-up with a made-up address gets nowhere.
 *
 * Only a stored "false" shuts the door: a session saved before the flag
 * existed says nothing, and the API still checks what matters.
 */
export const verifiedEmailGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.getCurrentUser()?.emailVerified === false ? router.createUrlTree(['/check-inbox']) : true;
};
