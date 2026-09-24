// libs/shared-auth/src/lib/auth.service.ts
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { TokenService } from './token.service';
import {
  clearSessionEndReason,
  rememberSessionEndReason,
  readSessionEndReason,
  SessionEndReason,
  SessionExpiry,
} from './session-expiry';
import { User, UserRole } from '@airral/shared-types';

@Injectable({
  providedIn: 'root'
})
export class AuthService {
  private currentUserSubject: BehaviorSubject<User | null>;
  public currentUser$: Observable<User | null>;

  private isAuthenticatedSubject: BehaviorSubject<boolean>;
  public isAuthenticated$: Observable<boolean>;

  constructor(private tokenService: TokenService) {
    // Initialize and clean up expired tokens
    this.initializeAuth();

    this.currentUserSubject = new BehaviorSubject<User | null>(this.tokenService.getUser());
    this.currentUser$ = this.currentUserSubject.asObservable();

    this.isAuthenticatedSubject = new BehaviorSubject<boolean>(this.tokenService.hasUnexpiredSession());
    this.isAuthenticated$ = this.isAuthenticatedSubject.asObservable();
  }

  /**
   * Discard a stored session we cannot vouch for, before anything reads it.
   *
   * <p>This used to fire only for a token whose `exp` said it had passed, which
   * an encrypted token never does, so in practice it never fired at all. Now it
   * also clears a session with no usable expiry record -- which is every
   * session stored before this change shipped. Those get signed out once, on
   * their next page load, and that is the intended cost: their real expiry is
   * not recoverable by any means (the token is a JWE the client cannot read and
   * there is no introspection endpoint), so the only honest readings are "gone"
   * or "we do not know", and this change exists because the second must not be
   * reported as fine.
   *
   * <p>The reason is kept so the login page can say which of the two happened.
   */
  private initializeAuth(): void {
    const reason = this.tokenService.sessionEndReason();
    if (reason) {
      console.warn(`Clearing stored session on initialization (${reason})`);
      rememberSessionEndReason(reason);
      this.tokenService.clear();
    }
  }

  /**
   * Why the stored session was discarded, if it was.
   *
   * <p>Reads without consuming. The guard redirects with a full page load and
   * can run more than once for one navigation, so a one-shot read left the
   * second run with nothing and its reason-less redirect won -- dropping the
   * user on a bare login form. The login page clears this once it has shown it.
   */
  sessionEndReason(): SessionEndReason | null {
    return readSessionEndReason();
  }

  /**
   * Establish a session, with the point at which it ends.
   *
   * <p>`expiry` is required. Every caller has to know when the session it is
   * creating runs out, so a path that cannot say is a build failure rather than
   * a session the client will later claim is valid forever.
   */
  login(user: User, token: string, expiry: SessionExpiry): void {
    clearSessionEndReason();
    this.tokenService.setToken(token, expiry);
    this.tokenService.setUser(user);
    this.currentUserSubject.next(user);
    this.isAuthenticatedSubject.next(true);
  }

  logout(): void {
    this.tokenService.clear();
    this.currentUserSubject.next(null);
    this.isAuthenticatedSubject.next(false);
  }

  getCurrentUser(): User | null {
    return this.currentUserSubject.value;
  }

  /**
   * Update facts about the signed-in user without a new session -- for instance
   * that their address was verified on another device. The session token is
   * untouched; the server reads verification from its database, not the token.
   */
  patchCurrentUser(changes: Partial<User>): void {
    const current = this.currentUserSubject.value;
    if (!current) return;
    const updated = { ...current, ...changes };
    this.tokenService.setUser(updated);
    this.currentUserSubject.next(updated);
  }

  isAuthenticated(): boolean {
    // Re-check, because a session can age out between page load and now.
    // "unexpired" is not "valid": the server also rejects a token inside its
    // exp once tokenVersion moves on, which is invisible from here, so the
    // interceptor's 401 handling remains the backstop.
    const isValid = this.isAuthenticatedSubject.value && this.tokenService.hasUnexpiredSession();

    // If state is out of sync, update it
    if (this.isAuthenticatedSubject.value !== isValid) {
      this.isAuthenticatedSubject.next(isValid);
      if (!isValid) {
        this.currentUserSubject.next(null);
      }
    }

    return isValid;
  }

  hasRole(role: UserRole | string): boolean {
    const user = this.getCurrentUser();
    return user ? user.roles.includes(role as string) : false;
  }

  hasAnyRole(...roles: (UserRole | string)[]): boolean {
    const user = this.getCurrentUser();
    return user ? roles.some(role => user.roles.includes(role as string)) : false;
  }

  getToken(): string | null {
    return this.tokenService.getToken();
  }
}
