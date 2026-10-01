import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from '@airral/shared-auth';
import { OrganizationService } from '@airral/shared-utils';
import { OrganizationTier } from '@airral/shared-types';
import {
  HrNavItem,
  getNavItemsForRole,
  getPrimaryRole,
  getRoleLabelForRole,
  filterNavByTier,
} from './feature-config';

import { VerifyEmailBannerComponent, AirralLogoComponent } from '@airral/shared-ui';

@Component({
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive, VerifyEmailBannerComponent, AirralLogoComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected title = 'hr-portal';
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly orgService = inject(OrganizationService);

  readonly menuOpen = signal(false);

  constructor() {
    // The organization is read from the stored user once, when the app boots.
    // Signing in without a page reload (the login form, an accepted invitation)
    // left the sidebar saying "Organization" until the next refresh, so it is
    // re-read whenever the signed-in user's company differs from the one shown.
    this.router.events.pipe(filter((event) => event instanceof NavigationEnd)).subscribe(() => {
      const organizationId = this.authService.getCurrentUser()?.organizationId;
      if (organizationId && organizationId !== this.orgService.organization.id) {
        this.orgService.loadOrganizationSettings();
      }
    });
  }

  get navItems(): HrNavItem[] {
    const allItems = getNavItemsForRole(this.primaryRole);
    const tier = this.orgService.tier;
    return filterNavByTier(allItems, tier);
  }

  get roleLabel(): string {
    return getRoleLabelForRole(this.primaryRole);
  }

  get userInitials(): string {
    const user = this.authService.getCurrentUser();
    const first = user?.firstName?.charAt(0) || user?.email?.charAt(0) || 'H';
    const last = user?.lastName?.charAt(0) || 'R';
    return `${first}${last}`.toUpperCase();
  }

  get userName(): string {
    const user = this.authService.getCurrentUser();
    const name = [user?.firstName, user?.lastName].filter(Boolean).join(' ');
    return name || user?.email || 'Signed in';
  }

  get tierLabel(): string {
    return this.orgService.getTierName();
  }

  get organizationName(): string {
    return this.orgService.organization.name;
  }

  get organizationContext(): string {
    return `${this.organizationName} · ${this.tierLabel}`;
  }

  get isQuickHireMode(): boolean {
    return this.orgService.tier === OrganizationTier.QUICK_HIRE;
  }

  toggleMenu(): void {
    this.menuOpen.update((value) => !value);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  logout(): void {
    this.authService.logout();

    window.location.href = '/login';
  }

  get isAuthRoute(): boolean {
    // The account pages render without the workspace shell: the person on them
    // may not be signed in at all.
    const url = this.router.url;
    return ['/login', '/verify-email', '/forgot-password', '/reset-password', '/accept-invitation', '/check-inbox'].some((p) => url.startsWith(p));
  }

  private get primaryRole(): string {
    const user = this.authService.getCurrentUser();
    return getPrimaryRole(user?.roles);
  }
}
