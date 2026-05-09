// libs/shared-utils/src/lib/tier-guard.service.ts
import { Injectable } from '@angular/core';
import { OrganizationTier } from '@airral/shared-types';
import { OrganizationService } from './organization.service';

export type FeatureGate = 'pro' | 'enterprise';

@Injectable({
  providedIn: 'root'
})
export class TierGuardService {
  constructor(private orgService: OrganizationService) {}

  /**
   * Check if user has access to a specific tier-gated feature
   */
  hasFeatureAccess(gate: FeatureGate): boolean {
    const tier = this.orgService.tier;

    switch (gate) {
      case 'pro':
        return tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE;
      case 'enterprise':
        return tier === OrganizationTier.ENTERPRISE;
      default:
        return true;
    }
  }

  /**
   * Check if user is on Free tier
   */
  get isFreeTier(): boolean {
    return this.orgService.tier === OrganizationTier.QUICK_HIRE;
  }

  /**
   * Check if user is on Pro tier or higher
   */
  get isProTier(): boolean {
    return this.hasFeatureAccess('pro');
  }

  /**
   * Check if user is on Enterprise tier
   */
  get isEnterpriseTier(): boolean {
    return this.hasFeatureAccess('enterprise');
  }

  /**
   * Get upgrade message for a locked feature
   */
  getUpgradeMessage(gate: FeatureGate): string {
    switch (gate) {
      case 'pro':
        return 'Upgrade to Professional to unlock this feature';
      case 'enterprise':
        return 'Upgrade to Enterprise to unlock this feature';
      default:
        return 'This feature requires a higher tier';
    }
  }

  /**
   * Show upgrade prompt (can be replaced with modal later)
   */
  showUpgradePrompt(gate: FeatureGate): void {
    const message = this.getUpgradeMessage(gate);
    alert(`🔒 ${message}\n\nContact sales@airral.com to upgrade your plan.`);
  }
}
