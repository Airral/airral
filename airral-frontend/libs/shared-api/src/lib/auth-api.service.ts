// libs/shared-api/src/lib/auth-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthResponse, GoogleAuthRequest, LoginRequest, RegisterRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

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

  /** Always answers the same way, whether or not the address has an account. */
  requestPasswordReset(email: string): Observable<{ message: string }> {
    return this.apiClient.post<{ message: string }>('/auth/forgot-password', { email });
  }

  resetPassword(token: string, password: string): Observable<{ reset: boolean; message: string }> {
    return this.apiClient.post<{ reset: boolean; message: string }>('/auth/reset-password', { token, password });
  }

  logout(): Observable<{ message: string }> {
    return this.apiClient.delete<{ message: string }>('/auth/logout');
  }
}
