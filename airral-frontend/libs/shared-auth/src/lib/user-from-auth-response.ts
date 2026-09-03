import { AuthResponse, User } from '@airral/shared-types';

/**
 * Build the session User from an /api/auth/* response.
 *
 * One place, because the three sign-in surfaces each built this by hand and
 * each picked a different subset of the response. The applicant login and the
 * website's employer sign-up both omitted organizationId, organizationName and
 * organizationTier -- so an employer arriving at the HR portal through either
 * of them landed with no organisation context and their workspace rendered as
 * the generic "Organization · Quick Hire" instead of their own company and
 * tier. The API returns those fields in both cases; only the frontend dropped
 * them.
 *
 * Every field is copied whether or not the current caller expects it: an
 * applicant simply has no organisation, and carrying undefined costs nothing,
 * whereas omitting a field silently breaks whichever portal needed it.
 */
export function userFromAuthResponse(
  response: AuthResponse,
  fallback: { email?: string; phone?: string } = {}
): User {
  const role = response.role || undefined;

  return {
    id: response.userId ?? 0,
    email: response.email || response.userEmail || fallback.email || '',
    firstName: response.firstName,
    lastName: response.lastName,
    phone: fallback.phone,

    organizationId: response.organizationId,
    organizationName: response.organizationName,
    organizationTier: response.organizationTier,

    role,
    roles: role ? [role] : [],
    isPlatformAdmin: response.isPlatformAdmin,
    isActive: true,
    emailVerified: response.emailVerified,
  };
}
