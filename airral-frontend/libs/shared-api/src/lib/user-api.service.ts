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
  /** HR only: take the person out of their department. */
  clearDepartment?: boolean;
}

/** The roles an invitation can give. */
export type InviteRole = 'HR_MANAGER' | 'MANAGER' | 'EMPLOYEE';

export interface InviteUserRequest {
  email: string;
  role: InviteRole;
  firstName?: string;
  lastName?: string;
  departmentId?: number;
}

/** An invitation as HR sees it. The link's token never comes back from the API. */
export interface Invitation {
  id: number;
  email: string;
  role: InviteRole;
  firstName?: string | null;
  lastName?: string | null;
  department?: string | null;
  expiresAt: string;
  createdAt?: string | null;
  /** Whether the invitation email went out this time; null when nothing was sent. */
  emailSent?: boolean | null;
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

  /** Invite someone to the company. They are emailed a link to set a password. */
  inviteUser(request: InviteUserRequest): Observable<Invitation> {
    return this.apiClient.post<Invitation>('/users/invite', request);
  }

  getPendingInvitations(): Observable<Invitation[]> {
    return this.apiClient.get<Invitation[]>('/users/invitations');
  }

  /** Email a pending invitation again, with a fresh week to accept it. */
  resendInvitation(id: number): Observable<Invitation> {
    return this.apiClient.post<Invitation>(`/users/invitations/${id}/resend`, {});
  }

  cancelInvitation(id: number): Observable<void> {
    return this.apiClient.delete<void>(`/users/invitations/${id}`);
  }

  /** Give a member another role. They are signed out so the new role takes effect. */
  changeRole(id: number, role: InviteRole): Observable<User> {
    return this.apiClient.put<User>(`/users/${id}/role`, { role });
  }

  /** Deactivate a member (they are signed out everywhere), or let them back in. */
  setActive(id: number, active: boolean): Observable<User> {
    return this.apiClient.put<User>(`/users/${id}/active`, { active });
  }
}
