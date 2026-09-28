import { User } from '@airral/shared-types';
import { ROLE_LABELS } from '@airral/shared-utils';

/** Roles that can be on an interview. */
export const INTERVIEWER_ROLES = ['HR_MANAGER', 'MANAGER', 'EMPLOYEE'];

/** The teammates who can interview, from the company's member list, by name. */
export function interviewersFrom(users: User[]): User[] {
  return users
    .filter((user) => user.isActive !== false && INTERVIEWER_ROLES.includes((user.role || user.roles?.[0] || '').toUpperCase()))
    .sort((a, b) => teammateName(a).localeCompare(teammateName(b)));
}

export function teammateName(user: User): string {
  return [user.firstName, user.lastName].filter((part) => part?.trim()).join(' ') || user.email;
}

export function teammateRole(user: User): string {
  const role = (user.role || user.roles?.[0] || '').toUpperCase();
  return ROLE_LABELS[role as keyof typeof ROLE_LABELS] ?? '';
}
