import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '@airral/shared-auth';
import { OrganizationService } from '@airral/shared-utils';
import { OrganizationTier } from '@airral/shared-types';
import { getPrimaryRole, getRoleSegment, getWorkspaceTitleForRole } from '../../feature-config';
import { QuickHireHomeComponent } from './quick-hire-home.component';

@Component({
  selector: 'app-workspace-home',
  standalone: true,
  imports: [CommonModule, RouterLink, QuickHireHomeComponent],
  templateUrl: './workspace-home.component.html',
  styleUrl: './workspace-home.component.css',
})
export class WorkspaceHomeComponent {
  private readonly authService = inject(AuthService);
  private readonly orgService = inject(OrganizationService);

  readonly role = getPrimaryRole(this.authService.getCurrentUser()?.roles);

  get isEmployee(): boolean {
    return getRoleSegment(this.role) === 'EMPLOYEE';
  }

  get isManager(): boolean {
    return getRoleSegment(this.role) === 'MANAGER';
  }

  get isHr(): boolean {
    return getRoleSegment(this.role) === 'HR';
  }

  get isQuickHire(): boolean {
    return this.orgService.tier === OrganizationTier.QUICK_HIRE;
  }

  get title(): string {
    return getWorkspaceTitleForRole(this.role);
  }
}
