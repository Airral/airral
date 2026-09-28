import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { InviteRole, Invitation, UserApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { User } from '@airral/shared-types';
import { ROLE_LABELS } from '@airral/shared-utils';

interface RoleOption {
  value: InviteRole;
  label: string;
  help: string;
}

/**
 * Who hires with this company: invite teammates, and see pending invitations
 * and members. An invitation emails a link that is good for 7 days.
 */
@Component({
  selector: 'app-team-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './team.component.html',
  styleUrl: './team.component.css',
})
export class TeamComponent implements OnInit {
  private readonly userApi = inject(UserApiService);
  private readonly meId = inject(AuthService).getCurrentUser()?.id ?? null;

  readonly roleOptions: RoleOption[] = [
    { value: 'MANAGER', label: ROLE_LABELS['MANAGER'], help: 'Reviews and interviews candidates for their jobs.' },
    { value: 'EMPLOYEE', label: ROLE_LABELS['EMPLOYEE'], help: 'Takes part in interviews.' },
    { value: 'HR_MANAGER', label: ROLE_LABELS['HR_MANAGER'], help: 'Runs hiring: jobs, candidates, offers, settings and the team.' },
  ];

  members: User[] = [];
  invitations: Invitation[] = [];
  loading = true;
  loadError = '';

  form: { email: string; firstName: string; lastName: string; role: InviteRole } = {
    email: '',
    firstName: '',
    lastName: '',
    role: 'MANAGER',
  };
  inviting = false;
  inviteMessage = '';
  inviteError = '';

  readonly busy = new Set<number>();
  rowMessage: Record<number, string> = {};
  readonly memberBusy = new Set<number>();
  memberMessage: Record<number, string> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    forkJoin({ members: this.userApi.getAllUsers(), invitations: this.userApi.getPendingInvitations() }).subscribe({
      next: ({ members, invitations }) => {
        this.members = members;
        this.invitations = invitations;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.loadError = 'We could not load your team. Reload the page to try again.';
      },
    });
  }

  roleLabel(role?: string | null): string {
    return ROLE_LABELS[role ?? ''] ?? role ?? '';
  }

  roleHelp(): string {
    return this.roleOptions.find((option) => option.value === this.form.role)?.help ?? '';
  }

  displayName(first?: string | null, last?: string | null, fallback = ''): string {
    const name = `${first ?? ''} ${last ?? ''}`.trim();
    return name || fallback;
  }

  invite(): void {
    const email = this.form.email.trim();
    if (this.inviting) return;
    if (!email) {
      this.inviteError = 'Enter the email address to invite.';
      return;
    }
    this.inviting = true;
    this.inviteError = '';
    this.inviteMessage = '';

    this.userApi
      .inviteUser({
        email,
        role: this.form.role,
        firstName: this.form.firstName.trim() || undefined,
        lastName: this.form.lastName.trim() || undefined,
      })
      .subscribe({
        next: (invitation) => {
          this.inviting = false;
          this.invitations = [invitation, ...this.invitations];
          this.inviteMessage =
            invitation.emailSent === false
              ? `Invitation saved for ${invitation.email}, but the email didn't go out. Use Resend below.`
              : `Invitation sent to ${invitation.email}.`;
          this.form = { email: '', firstName: '', lastName: '', role: this.form.role };
        },
        error: (error) => {
          this.inviting = false;
          this.inviteError = messageFrom(error, 'We could not send the invitation. Try again.');
        },
      });
  }

  resend(invitation: Invitation): void {
    if (this.busy.has(invitation.id)) return;
    this.busy.add(invitation.id);
    delete this.rowMessage[invitation.id];
    this.userApi.resendInvitation(invitation.id).subscribe({
      next: (updated) => {
        this.busy.delete(invitation.id);
        this.invitations = this.invitations.map((item) => (item.id === updated.id ? updated : item));
        this.rowMessage[invitation.id] =
          updated.emailSent === false ? "The email didn't go out. Try again in a minute." : 'Sent again.';
      },
      error: (error) => {
        this.busy.delete(invitation.id);
        this.rowMessage[invitation.id] = messageFrom(error, 'We could not resend it. Try again.');
      },
    });
  }

  isMe(member: User): boolean {
    return member.id === this.meId;
  }

  /** Teammates HR can manage here: not yourself, and not AIRRAL's own admins. */
  canManage(member: User): boolean {
    return !this.isMe(member) && member.role !== 'ADMIN' && member.isPlatformAdmin !== true;
  }

  changeRole(member: User, role: InviteRole): void {
    if (this.memberBusy.has(member.id) || role === member.role) return;
    this.memberBusy.add(member.id);
    delete this.memberMessage[member.id];
    this.userApi.changeRole(member.id, role).subscribe({
      next: (updated) => {
        this.memberBusy.delete(member.id);
        this.replaceMember(updated);
        this.memberMessage[member.id] = `Now ${this.roleLabel(updated.role).toLowerCase()}. They'll sign in again to use the new role.`;
      },
      error: (error) => {
        this.memberBusy.delete(member.id);
        // A copy re-renders the picker with the role the member still has.
        this.replaceMember({ ...member });
        this.memberMessage[member.id] = messageFrom(error, 'We could not change the role. Try again.');
      },
    });
  }

  toggleActive(member: User): void {
    if (this.memberBusy.has(member.id)) return;
    const active = member.isActive === false;
    this.memberBusy.add(member.id);
    delete this.memberMessage[member.id];
    this.userApi.setActive(member.id, active).subscribe({
      next: (updated) => {
        this.memberBusy.delete(member.id);
        this.replaceMember(updated);
        this.memberMessage[member.id] = active ? 'Reactivated. They can sign in again.' : 'Deactivated and signed out everywhere.';
      },
      error: (error) => {
        this.memberBusy.delete(member.id);
        this.memberMessage[member.id] = messageFrom(error, 'We could not change this account. Try again.');
      },
    });
  }

  private replaceMember(updated: User): void {
    this.members = this.members.map((item) => (item.id === updated.id ? updated : item));
  }

  cancel(invitation: Invitation): void {
    if (this.busy.has(invitation.id)) return;
    this.busy.add(invitation.id);
    delete this.rowMessage[invitation.id];
    this.userApi.cancelInvitation(invitation.id).subscribe({
      next: () => {
        this.busy.delete(invitation.id);
        this.invitations = this.invitations.filter((item) => item.id !== invitation.id);
      },
      error: (error) => {
        this.busy.delete(invitation.id);
        this.rowMessage[invitation.id] = messageFrom(error, 'We could not cancel it. Try again.');
      },
    });
  }
}

/** The server's own explanation when it gave one, otherwise the fallback. */
function messageFrom(error: unknown, fallback: string): string {
  const failure = error as { status?: number; error?: { message?: string; validationErrors?: Record<string, string> } };
  const validation = failure?.error?.validationErrors;
  if (validation && Object.keys(validation).length) {
    return Object.values(validation)[0];
  }
  if (failure?.status && failure.status < 500 && failure.error?.message) {
    return failure.error.message;
  }
  return fallback;
}
