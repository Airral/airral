// libs/shared-auth/src/lib/token.service.ts
import { Injectable } from '@angular/core';

import { AUTH_SESSION_EXPIRY_KEY, AUTH_TOKEN_KEY, AUTH_USER_KEY } from './auth-storage-keys';
import {
  SESSION_EXPIRY_GRACE_MS,
  SessionEndReason,
  SessionExpiry,
  SessionStatus,
} from './session-expiry';

const TOKEN_KEY = AUTH_TOKEN_KEY;
const USER_KEY = AUTH_USER_KEY;
const EXPIRY_KEY = AUTH_SESSION_EXPIRY_KEY;

/**
 * The tail of the token the expiry record describes.
 *
 * <p>Without this, a record left over from a previous session would be applied
 * to a new one: sign in again inside the old window and the fresh session is
 * killed on the spot, or worse, a partial write leaves a token with somebody
 * else's end time. A mismatch reads as unverifiable -- sign in once -- which is
 * always recoverable, where a wrongly-live session is not.
 */
function tokenFingerprint(token: string): string {
  return token.slice(-24);
}
const memoryStore = new Map<string, string>();

const memoryStorage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> = {
  getItem: (key: string) => memoryStore.get(key) ?? null,
  setItem: (key: string, value: string) => {
    memoryStore.set(key, value);
  },
  removeItem: (key: string) => {
    memoryStore.delete(key);
  },
};

type SafeStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function isStorageLike(value: unknown): value is SafeStorage {
  const candidate = value as SafeStorage | undefined;
  return (
    !!candidate &&
    typeof candidate.getItem === 'function' &&
    typeof candidate.setItem === 'function' &&
    typeof candidate.removeItem === 'function'
  );
}

@Injectable({
  providedIn: 'root'
})
export class TokenService {

  private getStorage(): SafeStorage {
    // localStorage is scoped per origin. Cross-port dev handoff is handled by auth-handoff.
    try {
      const storage = globalThis.localStorage;
      if (isStorageLike(storage)) {
        return storage;
      }
    } catch {
      return memoryStorage;
    }

    return memoryStorage;
  }

  private getSessionStorage(): Pick<Storage, 'getItem' | 'removeItem'> | null {
    try {
      const storage = globalThis.sessionStorage;
      return isStorageLike(storage) ? storage : null;
    } catch {
      return null;
    }
  }

  /**
   * Store the token together with when the session ends.
   *
   * <p>The expiry is required, not optional. Every path that mints a session
   * has to say when it ends, so a path that forgets is a compile error rather
   * than a session nobody can judge -- which is the shape of the defect this
   * replaces.
   */
  setToken(token: string, expiry: SessionExpiry): void {
    // Record first, token second, and the order is the point. If the second
    // write fails -- a storage quota, a browser clearing site data mid-write --
    // this way leaves a record with no token, which reads as 'none' and simply
    // shows a login form. The other order leaves a token with no record, which
    // reads as unverifiable and signs the user out immediately after they
    // signed in.
    this.getStorage().setItem(
      EXPIRY_KEY,
      JSON.stringify({ expiresAt: expiry.expiresAt, src: expiry.source, tk: tokenFingerprint(token) })
    );
    this.getStorage().setItem(TOKEN_KEY, token);
  }

  getToken(): string | null {
    // Fallback to sessionStorage for older sessions created before this change.
    return this.getStorage().getItem(TOKEN_KEY) ?? this.getSessionStorage()?.getItem(TOKEN_KEY) ?? null;
  }

  removeToken(): void {
    this.getStorage().removeItem(TOKEN_KEY);
    this.getStorage().removeItem(EXPIRY_KEY);
    this.getSessionStorage()?.removeItem(TOKEN_KEY);
  }

  /** The stored expiry, if there is one that describes the current token. */
  getSessionExpiry(): SessionExpiry | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }

    // Read from localStorage only, with no sessionStorage fallback, and that is
    // deliberate: nothing in this repo has ever written the expiry to
    // sessionStorage, so a token found there came from a build that predates
    // this and provably has no sidecar. It stays unverifiable.
    const raw = this.getStorage().getItem(EXPIRY_KEY);
    if (!raw) {
      return null;
    }

    try {
      const parsed = JSON.parse(raw) as { expiresAt?: unknown; src?: unknown; tk?: unknown };
      if (typeof parsed.expiresAt !== 'number' || !Number.isFinite(parsed.expiresAt)) {
        return null;
      }
      if (parsed.tk !== tokenFingerprint(token)) {
        return null;
      }
      return {
        expiresAt: parsed.expiresAt,
        source: parsed.src === 'server' ? 'server' : 'assumed',
      };
    } catch {
      return null;
    }
  }

  setUser(user: any): void {
    this.getStorage().setItem(USER_KEY, JSON.stringify(user));
  }

  getUser(): any {
    const user = this.getStorage().getItem(USER_KEY) ?? this.getSessionStorage()?.getItem(USER_KEY);
    return user ? JSON.parse(user) : null;
  }

  removeUser(): void {
    this.getStorage().removeItem(USER_KEY);
    this.getSessionStorage()?.removeItem(USER_KEY);
  }

  hasToken(): boolean {
    return !!this.getToken();
  }

  /**
   * What we can honestly say about the stored session.
   *
   * <p>This replaced `isTokenExpired`, which answered a two-state question the
   * client could not answer. For an encrypted token -- which is every token
   * this backend issues -- it returned `false`, meaning "not expired", always
   * and forever, while the server expired the token after 24 hours. The three
   * things that consumed it therefore believed any past sign-in was current.
   *
   * <p>`unverifiable` is the state that did not exist before and is the whole
   * point. A session we cannot place in time is not reported as fine.
   */
  sessionStatus(): SessionStatus {
    const token = this.getToken();
    if (!token) {
      return 'none';
    }

    const parsedToken = this.parseToken(token);
    if (!parsedToken || !this.isAllowedToken(parsedToken)) {
      return 'unverifiable';
    }

    const expiry = this.getSessionExpiry();
    if (!expiry) {
      return 'unverifiable';
    }

    return Date.now() > expiry.expiresAt + SESSION_EXPIRY_GRACE_MS ? 'expired' : 'unexpired';
  }

  /**
   * Why a stored session should be discarded, or null to keep it.
   *
   * <p>Separate from the status so the two reasons can be said differently to a
   * person: one is "your session ran out", the other is "we could not confirm
   * your session". Reporting the second as the first would be a small lie in
   * the one place this change exists to stop lying.
   */
  sessionEndReason(): SessionEndReason | null {
    const status = this.sessionStatus();
    return status === 'expired' || status === 'unverifiable' ? status : null;
  }

  /**
   * True when the session is not known to have ended.
   *
   * <p>Named for what it can actually establish. It is NOT `isTokenValid`,
   * which it replaced: the server also rejects a token well inside its `exp`
   * once the user's `tokenVersion` moves on, and the client cannot see that. So
   * this is never permission -- the interceptor's 401 handling stays the thing
   * that catches a session the server has disowned.
   */
  hasUnexpiredSession(): boolean {
    return this.sessionStatus() === 'unexpired';
  }

  /**
   * `getTokenExpiry` was removed here rather than fixed.
   *
   * <p>It read the `exp` claim out of the token and returned null for an
   * encrypted one -- which is every token this backend issues -- so it always
   * answered null, and it had no callers anywhere in the four apps. Keeping it
   * would leave two ways to ask when the session ends, one of which cannot
   * work. {@link getSessionExpiry} is the one that can.
   */

  clear(): void {
    this.removeToken();
    this.removeUser();
  }

  private parseToken(token: string): { header: any; payload: any | null; encrypted: boolean } | null {
    const parts = token.split('.');
    if (parts.length !== 3 && parts.length !== 5) {
      return null;
    }

    const header = JSON.parse(this.base64UrlDecode(parts[0]));
    if (parts.length === 5) {
      return { header, payload: null, encrypted: true };
    }

    return {
      header,
      payload: JSON.parse(this.base64UrlDecode(parts[1])),
      encrypted: false,
    };
  }

  private base64UrlDecode(value: string): string {
    const normalized = value
      .replace(/-/g, '+')
      .replace(/_/g, '/')
      .padEnd(Math.ceil(value.length / 4) * 4, '=');

    return atob(normalized);
  }

  private isAllowedToken(parsedToken: { header: any; payload: any | null; encrypted: boolean }): boolean {
    if (parsedToken.encrypted) {
      return parsedToken.header?.alg === 'dir' && parsedToken.header?.enc === 'A256GCM';
    }

    return false;
  }
}
