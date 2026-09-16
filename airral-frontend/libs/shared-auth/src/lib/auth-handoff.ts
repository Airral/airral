import { AuthService } from './auth.service';
import { User } from '@airral/shared-types';
import { AUTH_SESSION_EXPIRY_KEY, AUTH_TOKEN_KEY, AUTH_USER_KEY } from './auth-storage-keys';
import { ASSUMED_SESSION_LIFETIME_MS, SessionExpiry } from './session-expiry';

const AUTH_HANDOFF_KEY = 'airralAuth';

/**
 * The expiry a handoff fragment describes.
 *
 * <p>Absolute across the fragment, unlike the relative seconds used on the
 * wire, because both ends are the same browser reading the same clock -- so an
 * instant transfers exactly and no re-anchoring is needed.
 *
 * <p>A fragment without one is accepted and labelled `assumed`. It means the
 * bundle that built it predates this change, which happens on every deploy
 * while caches drain and for as long as somebody keeps a tab open. Refusing
 * those would break cross-portal sign-in for the whole rollout, and the admin
 * portal has no other door. Labelling is what keeps it honest: the session
 * works, and nothing claims the server vouched for its end time.
 */
function expiryFromHandoff(expiresAt: unknown, now: number = Date.now()): SessionExpiry {
  if (typeof expiresAt === 'number' && Number.isFinite(expiresAt) && expiresAt > now) {
    return { expiresAt, source: 'server' };
  }
  return { expiresAt: now + ASSUMED_SESSION_LIFETIME_MS, source: 'assumed' };
}

/** Written by both consumers, so neither can leave a session nobody can judge. */
function writeHandoffExpiry(token: string, expiry: SessionExpiry): void {
  window.localStorage.setItem(
    AUTH_SESSION_EXPIRY_KEY,
    JSON.stringify({ expiresAt: expiry.expiresAt, src: expiry.source, tk: token.slice(-24) })
  );
}

export function buildLocalAuthHandoffUrl(
  targetUrl: string,
  user: User,
  token: string,
  expiry: SessionExpiry
): string {
  if (!isTrustedHandoffTarget(targetUrl)) {
    return targetUrl;
  }

  const url = new URL(targetUrl, window.location.origin);
  const hashParams = new URLSearchParams(url.hash.replace(/^#/, ''));
  hashParams.set(
    AUTH_HANDOFF_KEY,
    base64UrlEncode(JSON.stringify({ token, user, expiresAt: expiry.expiresAt }))
  );
  url.hash = hashParams.toString();
  return url.toString();
}

/**
 * Consume a handoff fragment before the application bootstraps.
 *
 * consumeLocalAuthHandoff() below also strips the fragment, but that strip does
 * not survive. By the time a guard runs, Angular's Router has already parsed
 * the initial URL into a UrlTree that includes the fragment, and it
 * re-serialises that tree when the navigation completes -- putting the token
 * straight back into the address bar, and into browser history with it.
 * Verified in production: after a cross-portal sign-in the address bar still
 * held 922 characters of session token.
 *
 * Running before bootstrap means the Router never observes a fragment it could
 * restore. The session is written to storage directly rather than through
 * AuthService, which does not exist yet; both read the same keys.
 *
 * Fragments are never sent to a server and are stripped from Referer, so this
 * was not an over-the-wire leak -- but a bearer credential in history is one
 * shared URL or synced profile away from being someone else's session.
 */
export function consumeAuthHandoffBeforeBootstrap(): void {
  if (typeof window === 'undefined' || !isTrustedCurrentHost()) {
    return;
  }

  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const encoded = hashParams.get(AUTH_HANDOFF_KEY);
  if (!encoded) {
    return;
  }

  // Strip first and unconditionally: a malformed payload must not leave the
  // fragment sitting in the URL just because it failed to parse.
  hashParams.delete(AUTH_HANDOFF_KEY);
  cleanUrl(hashParams.toString());

  try {
    const parsed = JSON.parse(base64UrlDecode(encoded)) as {
      token?: string;
      user?: User;
      expiresAt?: unknown;
    };
    if (!parsed.token || !parsed.user) {
      return;
    }
    // AuthService does not exist yet on this path, so the expiry is written
    // directly alongside the token. Omitting it would hand the app a session
    // with no end time, which reads as unverifiable and signs the arriving user
    // straight back out -- breaking the very handoff this exists to complete.
    //
    // Written before the token, so that a write failing part-way leaves a
    // record with no token (which reads as 'none', a login form) rather than a
    // token with no record (which reads as unverifiable, an instant sign-out).
    writeHandoffExpiry(parsed.token, expiryFromHandoff(parsed.expiresAt));
    window.localStorage.setItem(AUTH_TOKEN_KEY, parsed.token);
    window.localStorage.setItem(AUTH_USER_KEY, JSON.stringify(parsed.user));
  } catch {
    // Nothing to restore. The fragment is already gone.
  }
}

export function consumeLocalAuthHandoff(authService: AuthService): boolean {
  if (!isTrustedCurrentHost()) {
    return false;
  }

  const hashParams = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const encoded = hashParams.get(AUTH_HANDOFF_KEY);
  if (!encoded) {
    return false;
  }

  try {
    const parsed = JSON.parse(base64UrlDecode(encoded)) as {
      token?: string;
      user?: User;
      expiresAt?: unknown;
    };
    if (!parsed.token || !parsed.user) {
      return false;
    }

    authService.login(parsed.user, parsed.token, expiryFromHandoff(parsed.expiresAt));
    hashParams.delete(AUTH_HANDOFF_KEY);
    scheduleCleanUrl(hashParams.toString());
    return true;
  } catch {
    hashParams.delete(AUTH_HANDOFF_KEY);
    scheduleCleanUrl(hashParams.toString());
    return false;
  }
}

function isTrustedHandoffTarget(targetUrl: string): boolean {
  try {
    const url = new URL(targetUrl, window.location.origin);
    return isTrustedHost(url.hostname);
  } catch {
    return false;
  }
}

function isTrustedCurrentHost(): boolean {
  return isTrustedHost(window.location.hostname);
}

function isTrustedHost(hostname: string): boolean {
  const normalizedHost = hostname.toLowerCase();
  return isLocalHostname(normalizedHost) || normalizedHost === 'airral.com' || normalizedHost.endsWith('.airral.com');
}

function isLocalHostname(hostname: string): boolean {
  return ['localhost', '127.0.0.1', '0.0.0.0'].includes(hostname.toLowerCase());
}

function scheduleCleanUrl(hash: string): void {
  cleanUrl(hash);
  window.setTimeout(() => cleanUrl(hash));
}

function cleanUrl(hash: string): void {
  if (!window.history?.replaceState) {
    return;
  }

  const nextHash = hash ? `#${hash}` : '';
  const searchParams = new URLSearchParams(window.location.search);
  const search = searchParams.toString();
  const nextSearch = search ? `?${search}` : '';
  window.history.replaceState({}, document.title, `${window.location.pathname}${nextSearch}${nextHash}`);
}

function base64UrlEncode(value: string): string {
  const bytes = new TextEncoder().encode(value);
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join('');

  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function base64UrlDecode(value: string): string {
  const normalized = value
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=');

  const binary = atob(normalized);
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}
