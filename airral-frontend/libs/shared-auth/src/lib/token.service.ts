// libs/shared-auth/src/lib/token.service.ts
import { Injectable } from '@angular/core';

const TOKEN_KEY = 'auth_token';
const USER_KEY = 'current_user';

@Injectable({
  providedIn: 'root'
})
export class TokenService {

  private getStorage(): Storage {
    // localStorage is shared across localhost ports, which is required for multi-portal auth in dev.
    return localStorage;
  }

  setToken(token: string): void {
    this.getStorage().setItem(TOKEN_KEY, token);
  }

  getToken(): string | null {
    // Fallback to sessionStorage for older sessions created before this change.
    return this.getStorage().getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
  }

  removeToken(): void {
    this.getStorage().removeItem(TOKEN_KEY);
    sessionStorage.removeItem(TOKEN_KEY);
  }

  setUser(user: any): void {
    this.getStorage().setItem(USER_KEY, JSON.stringify(user));
  }

  getUser(): any {
    const user = this.getStorage().getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
    return user ? JSON.parse(user) : null;
  }

  removeUser(): void {
    this.getStorage().removeItem(USER_KEY);
    sessionStorage.removeItem(USER_KEY);
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
      // JWT format: header.payload.signature
      const payload = JSON.parse(atob(token.split('.')[1]));

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
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload.exp ? payload.exp * 1000 : null;
    } catch {
      return null;
    }
  }

  clear(): void {
    this.removeToken();
    this.removeUser();
  }
}
