import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '@airral/shared-auth';
import { OrganizationService } from '@airral/shared-utils';
import { OrganizationTier } from '@airral/shared-types';
import {
  HrNavItem,
  getNavItemsForRole,
  getPrimaryRole,
  getRoleLabelForRole,
  getSearchLabelForRole,
  filterNavByTier,
} from './feature-config';

@Component({
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected title = 'hr-portal';
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly orgService = inject(OrganizationService);

  readonly menuOpen = signal(false);

  ngOnInit(): void {}

  get navItems(): HrNavItem[] {
    const allItems = getNavItemsForRole(this.primaryRole);
    const tier = this.orgService.tier;
    return filterNavByTier(allItems, tier);
  }

  get roleLabel(): string {
    return getRoleLabelForRole(this.primaryRole);
  }

  get searchLabel(): string {
    return getSearchLabelForRole(this.primaryRole);
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
    return this.router.url.startsWith('/login');
  }

  private get primaryRole(): string {
    const user = this.authService.getCurrentUser();
    return getPrimaryRole(user?.roles);
  }
}
