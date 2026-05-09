// libs/shared-auth/src/lib/auth.service.ts
import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { TokenService } from './token.service';
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

    this.isAuthenticatedSubject = new BehaviorSubject<boolean>(this.tokenService.isTokenValid());
    this.isAuthenticated$ = this.isAuthenticatedSubject.asObservable();
  }

  /**
   * Initialize authentication state and clear expired tokens
   */
  private initializeAuth(): void {
    if (this.tokenService.hasToken() && this.tokenService.isTokenExpired()) {
      console.warn('Found expired token on initialization - clearing');
      this.tokenService.clear();
    }
  }

  login(user: User, token: string): void {
    this.tokenService.setToken(token);
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

  isAuthenticated(): boolean {
    // Double-check token validity
    const isValid = this.isAuthenticatedSubject.value && this.tokenService.isTokenValid();

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
