// libs/shared-api/src/lib/auth-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthResponse, GoogleAuthRequest, LoginRequest, RegisterRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

export interface AccountStatus {
  userId: number;
  email: string;
  role: string;
  emailVerified: boolean;
  organizationId?: number | null;
  organizationName?: string;
  /** PENDING, VERIFIED or REJECTED. A company's jobs reach candidates only when VERIFIED. */
  organizationVerificationStatus?: string;
}

@Injectable({
  providedIn: 'root'
})
export class AuthApiService {
  constructor(private apiClient: ApiClientService) {}

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.apiClient.post<AuthResponse>('/auth/login', request);
  }

  googleLogin(request: GoogleAuthRequest): Observable<AuthResponse> {
    return this.apiClient.post<AuthResponse>('/auth/google', request);
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.apiClient.post<AuthResponse>('/auth/register', request);
  }

  /**
   * Ask for a reset link. AIRRAL sends one only if the address has an account,
   * and answers the same way -- after the same short wait -- either way.
   */
  forgotPassword(email: string): Observable<{ message: string }> {
    return this.apiClient.post<{ message: string }>('/auth/forgot-password', { email });
  }

  /** Email the signed-in account a new verification link, to its own address. */
  sendVerification(): Observable<{ sent: boolean; alreadyVerified: boolean }> {
    return this.apiClient.post<{ sent: boolean; alreadyVerified: boolean }>('/auth/send-verification', {});
  }

  /** Hand AIRRAL the Firebase ID token proving the address was followed from its link. */
  verifyEmail(idToken: string): Observable<{ verified: boolean; email: string; message: string }> {
    return this.apiClient.post<{ verified: boolean; email: string; message: string }>('/auth/verify-email', { idToken });
  }

  /** Set a new password; the Firebase ID token proves the reset link was followed. */
  resetPassword(idToken: string, password: string): Observable<{ reset: boolean; message: string }> {
    return this.apiClient.post<{ reset: boolean; message: string }>('/auth/reset-password', { idToken, password });
  }

  /** The caller as the database sees them now, not as the session token remembers. */
  me(): Observable<AccountStatus> {
    return this.apiClient.get<AccountStatus>('/auth/me');
  }

  logout(): Observable<{ message: string }> {
    return this.apiClient.delete<{ message: string }>('/auth/logout');
  }
}
