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

export interface InviteUserRequest {
  email: string;
  role: string;
  firstName?: string;
  lastName?: string;
  departmentId?: number;
  department?: string;
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
   * Get team members for a manager
   */
  getTeamMembers(managerId: number): Observable<User[]> {
    return this.apiClient.get<User[]>(`/users/team/${managerId}`);
  }

  /**
   * Update user profile
   */
  updateUser(id: number, request: UpdateUserRequest): Observable<User> {
    return this.apiClient.put<User>(`/users/${id}`, request);
  }

  /**
   * Invite a new team member
   */
  inviteUser(request: InviteUserRequest): Observable<any> {
    return this.apiClient.post<any>('/users/invite', request);
  }

  /**
   * Get pending invitations
   */
  getInvitations(): Observable<any[]> {
    return this.apiClient.get<any[]>('/users/invitations');
  }
}
