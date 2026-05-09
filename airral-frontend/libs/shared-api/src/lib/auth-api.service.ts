// libs/shared-api/src/lib/auth-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthResponse, LoginRequest, RegisterRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class AuthApiService {
  constructor(private apiClient: ApiClientService) {}

  login(request: LoginRequest): Observable<AuthResponse> {
    return this.apiClient.post<AuthResponse>('/auth/login', request);
  }

  register(request: RegisterRequest): Observable<AuthResponse> {
    return this.apiClient.post<AuthResponse>('/auth/register', request);
  }

  logout(): Observable<{ message: string }> {
    return this.apiClient.delete<{ message: string }>('/auth/logout');
  }
}
