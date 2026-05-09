import { Injectable, signal } from '@angular/core';
import {
  Organization,
  OrganizationTier,
  FeatureFlags,
  getFeaturesForTier
} from '@airral/shared-types';

@Injectable({
  providedIn: 'root'
})
export class OrganizationService {
  private readonly organizationSignal = signal<Organization>(this.getOrganizationFromUser());

  get organization() {
    return this.organizationSignal();
  }

  get tier(): OrganizationTier {
    return this.organizationSignal().tier;
  }

  get features(): FeatureFlags {
    return getFeaturesForTier(this.tier);
  }

  isFeatureEnabled(feature: keyof FeatureFlags): boolean {
    return this.features[feature];
  }

  setTier(tier: OrganizationTier): void {
    this.organizationSignal.update(org => ({ ...org, tier }));
  }

  updateOrganization(updates: Partial<Organization>): void {
    this.organizationSignal.update(org => ({ ...org, ...updates }));
  }

  loadOrganizationSettings(): void {
    this.organizationSignal.set(this.getOrganizationFromUser());
  }

  getTierName(tier?: OrganizationTier): string {
    const t = tier || this.tier;
    switch(t) {
      case OrganizationTier.QUICK_HIRE:
        return 'Quick Hire';
      case OrganizationTier.PROFESSIONAL:
        return 'Professional';
      case OrganizationTier.ENTERPRISE:
        return 'Enterprise';
    }
  }

  getTierDescription(tier?: OrganizationTier): string {
    const t = tier || this.tier;
    switch(t) {
      case OrganizationTier.QUICK_HIRE:
        return 'Fast and simple hiring for small teams';
      case OrganizationTier.PROFESSIONAL:
        return 'Structured hiring process for growing companies';
      case OrganizationTier.ENTERPRISE:
        return 'Advanced features for large organizations';
    }
  }

  private getOrganizationFromUser(): Organization {
    const storedUser = this.getStoredUser();
    const detectedTier = this.normalizeTier(storedUser?.organizationTier);

    return {
      id: storedUser?.organizationId ?? 0,
      name: storedUser?.organizationName || 'Organization',
      tier: detectedTier,
      createdAt: new Date(),
    };
  }

  private getStoredUser(): {
    organizationId?: number;
    organizationName?: string;
    organizationTier?: string;
  } | null {
    const raw = localStorage.getItem('current_user') ?? sessionStorage.getItem('current_user');
    if (!raw) {
      return null;
    }

    try {
      return JSON.parse(raw) as {
        organizationId?: number;
        organizationName?: string;
        organizationTier?: string;
      };
    } catch {
      return null;
    }
  }

  private normalizeTier(value?: string): OrganizationTier {
    if (!value) {
      return OrganizationTier.QUICK_HIRE;
    }

    const normalized = value.toUpperCase();
    if (normalized === OrganizationTier.PROFESSIONAL) {
      return OrganizationTier.PROFESSIONAL;
    }
    if (normalized === OrganizationTier.ENTERPRISE) {
      return OrganizationTier.ENTERPRISE;
    }
    return OrganizationTier.QUICK_HIRE;
  }
}
