// libs/shared-api/src/lib/user-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { User } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

export interface UpdateUserRequest {
  firstName?: string;
  lastName?: string;
  phone?: string;
  department?: string;
  jobTitle?: string;
  departmentId?: number;
  managerId?: number;
}

@Injectable({
  providedIn: 'root'
})
export class UserApiService {
  constructor(private apiClient: ApiClientService) {}

  /**
   * Get all users in the organization
   */
  getAllUsers(): Observable<User[]> {
    return this.apiClient.get<User[]>('/users');
  }

  /**
   * Get user by ID
   */
  getUserById(id: number): Observable<User> {
    return this.apiClient.get<User>(`/users/${id}`);
  }

  /**
   * Update user profile
   */
  updateUser(id: number, request: UpdateUserRequest): Observable<User> {
    return this.apiClient.put<User>(`/users/${id}`, request);
  }
}
