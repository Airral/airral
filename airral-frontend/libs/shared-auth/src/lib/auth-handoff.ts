import { AuthService } from './auth.service';
import { User } from '@airral/shared-types';

const AUTH_HANDOFF_KEY = 'airralAuth';

export function buildLocalAuthHandoffUrl(targetUrl: string, user: User, token: string): string {
  if (!isTrustedHandoffTarget(targetUrl)) {
    return targetUrl;
  }

  const url = new URL(targetUrl, window.location.origin);
  const hashParams = new URLSearchParams(url.hash.replace(/^#/, ''));
  hashParams.set(AUTH_HANDOFF_KEY, base64UrlEncode(JSON.stringify({ token, user })));
  url.hash = hashParams.toString();
  return url.toString();
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
    const parsed = JSON.parse(base64UrlDecode(encoded)) as { token?: string; user?: User };
    if (!parsed.token || !parsed.user) {
      return false;
    }

    authService.login(parsed.user, parsed.token);
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
