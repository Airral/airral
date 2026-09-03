// libs/shared-auth/src/lib/token.service.ts
import { Injectable } from '@angular/core';

import { AUTH_TOKEN_KEY, AUTH_USER_KEY } from './auth-storage-keys';

const TOKEN_KEY = AUTH_TOKEN_KEY;
const USER_KEY = AUTH_USER_KEY;
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

  setToken(token: string): void {
    this.getStorage().setItem(TOKEN_KEY, token);
  }

  getToken(): string | null {
    // Fallback to sessionStorage for older sessions created before this change.
    return this.getStorage().getItem(TOKEN_KEY) ?? this.getSessionStorage()?.getItem(TOKEN_KEY) ?? null;
  }

  removeToken(): void {
    this.getStorage().removeItem(TOKEN_KEY);
    this.getSessionStorage()?.removeItem(TOKEN_KEY);
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
   * Check if the current token is expired
   * @returns true if token is expired or invalid, false if valid
   */
  isTokenExpired(): boolean {
    const token = this.getToken();
    if (!token) {
      return true;
    }

    try {
      const parsedToken = this.parseToken(token);
      if (!parsedToken || !this.isAllowedToken(parsedToken)) {
        return true;
      }

      if (parsedToken.encrypted) {
        return false;
      }

      const payload = parsedToken.payload;

      // exp claim is in seconds, convert to milliseconds
      if (!payload.exp) {
        return true;
      }

      const expiryTime = payload.exp * 1000;
      const now = Date.now();

      // Add 30 second buffer to account for clock skew
      return now >= (expiryTime - 30000);
    } catch (error) {
      console.error('Error parsing JWT token:', error);
      return true;
    }
  }

  /**
   * Check if token exists and is not expired
   * @returns true if token is valid and not expired
   */
  isTokenValid(): boolean {
    return this.hasToken() && !this.isTokenExpired();
  }

  /**
   * Get token expiration time in milliseconds
   * @returns expiration timestamp or null if invalid
   */
  getTokenExpiry(): number | null {
    const token = this.getToken();
    if (!token) {
      return null;
    }

    try {
      const parsedToken = this.parseToken(token);
      if (!parsedToken || !this.isAllowedToken(parsedToken)) {
        return null;
      }

      if (parsedToken.encrypted) {
        return null;
      }

      return parsedToken.payload.exp ? parsedToken.payload.exp * 1000 : null;
    } catch {
      return null;
    }
  }

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
