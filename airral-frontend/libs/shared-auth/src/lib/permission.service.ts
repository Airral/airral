// libs/shared-auth/src/lib/permission.service.ts
import { Injectable, inject } from '@angular/core';
import { AuthService } from './auth.service';
import { UserRole, OrganizationTier } from '@airral/shared-types';
import { OrganizationService } from '@airral/shared-utils';

/**
 * Permission service that combines role-based and tier-based access control
 *
 * Role controls WHAT you can do (post jobs, create reviews, etc.)
 * Tier controls HOW DEEP features go (basic ATS vs AI ATS, payroll, etc.)
 */
@Injectable({
  providedIn: 'root'
})
export class PermissionService {
  private authService = inject(AuthService);
  private orgService = inject(OrganizationService);

  // ==================== ROLE-BASED PERMISSIONS ====================

  /**
   * Can user post jobs? (ADMIN/HR_MANAGER only)
   */
  canPostJobs(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  /**
   * Can user view all applicants with ATS scores? (ADMIN/HR_MANAGER only)
   */
  canViewAllApplicants(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  /**
   * Can user add employees to system? (ADMIN/HR_MANAGER only)
   */
  canAddEmployees(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  /**
   * Can user create yearly reviews? (Managers only)
   */
  canCreateReviews(): boolean {
    return this.hasRole(UserRole.MANAGER);
  }

  /**
   * Can user submit referrals? (Everyone except applicants)
   */
  canSubmitReferrals(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER, UserRole.MANAGER, UserRole.EMPLOYEE);
  }

  /**
   * Can user view own benefits? (ADMIN/HR_MANAGER, Managers and Employees)
   */
  canViewOwnBenefits(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER, UserRole.MANAGER, UserRole.EMPLOYEE);
  }

  /**
   * Can user view own reviews? (ADMIN/HR_MANAGER, Managers and Employees)
   */
  canViewOwnReviews(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER, UserRole.MANAGER, UserRole.EMPLOYEE);
  }

  /**
   * Can user manage settings? (ADMIN/HR_MANAGER only)
   */
  canManageSettings(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  /**
   * Can user view team members? (ADMIN/HR_MANAGER and Managers)
   */
  canViewTeam(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER, UserRole.MANAGER);
  }

  /**
   * Can user review candidates? (ADMIN/HR_MANAGER only, or Managers if granted department access)
   */
  canReviewCandidates(): boolean {
    // For now, ADMIN/HR_MANAGER only. Later: check if manager has been granted access
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  /**
   * Can user connect LinkedIn? (ADMIN/HR_MANAGER only)
   */
  canConnectLinkedIn(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER);
  }

  // ==================== TIER-BASED FEATURES ====================

  /**
   * Can user access AI-powered ATS scoring? (Professional and Enterprise tiers)
   */
  canUseAIATS(): boolean {
    const tier = this.orgService.tier;
    return tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE;
  }

  /**
   * Can user access weighted keyword matching? (Professional and Enterprise tiers)
   */
  canUseWeightedKeywords(): boolean {
    const tier = this.orgService.tier;
    return tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE;
  }

  /**
   * Can user access analytics? (Professional and Enterprise tiers)
   */
  canViewAnalytics(): boolean {
    const tier = this.orgService.tier;
    return tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE;
  }

  /**
   * Can user access payroll? (Enterprise tier + ADMIN/HR_MANAGER role)
   */
  canAccessPayroll(): boolean {
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER) && this.orgService.tier === OrganizationTier.ENTERPRISE;
  }

  /**
   * Can user access advanced reporting? (Professional and Enterprise tiers + ADMIN/HR_MANAGER role)
   */
  canAccessAdvancedReporting(): boolean {
    const tier = this.orgService.tier;
    return this.hasAnyRole(UserRole.ADMIN, UserRole.HR_MANAGER) &&
           (tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE);
  }

  // ==================== COMBINED PERMISSIONS ====================

  /**
   * Get all permissions for current user (useful for UI rendering)
   */
  getCurrentPermissions() {
    return {
      // Role-based
      canPostJobs: this.canPostJobs(),
      canViewAllApplicants: this.canViewAllApplicants(),
      canAddEmployees: this.canAddEmployees(),
      canCreateReviews: this.canCreateReviews(),
      canSubmitReferrals: this.canSubmitReferrals(),
      canViewOwnBenefits: this.canViewOwnBenefits(),
      canViewOwnReviews: this.canViewOwnReviews(),
      canManageSettings: this.canManageSettings(),
      canViewTeam: this.canViewTeam(),
      canReviewCandidates: this.canReviewCandidates(),
      canConnectLinkedIn: this.canConnectLinkedIn(),

      // Tier-based
      canUseAIATS: this.canUseAIATS(),
      canUseWeightedKeywords: this.canUseWeightedKeywords(),
      canViewAnalytics: this.canViewAnalytics(),
      canAccessPayroll: this.canAccessPayroll(),
      canAccessAdvancedReporting: this.canAccessAdvancedReporting(),

      // Current state
      role: this.getCurrentRole(),
      tier: this.orgService.tier,
    };
  }

  // ==================== HELPER METHODS ====================

  private hasRole(role: UserRole): boolean {
    return this.authService.hasRole(role);
  }

  private hasAnyRole(...roles: UserRole[]): boolean {
    return this.authService.hasAnyRole(...roles);
  }

  private getCurrentRole(): UserRole | null {
    const user = this.authService.getCurrentUser();
    if (!user || !user.roles || user.roles.length === 0) {
      return null;
    }
    return user.roles[0] as UserRole;
  }
}
