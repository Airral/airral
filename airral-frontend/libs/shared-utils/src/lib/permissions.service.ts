// libs/shared-utils/src/lib/permissions.service.ts
import { Injectable, inject } from '@angular/core';
import { UserRole, OrganizationTier } from '@airral/shared-types';
import { AuthService } from '@airral/shared-auth';
import { OrganizationService } from './organization.service';

export interface Permission {
  // Job Posting & Management
  canPostJobs: boolean;
  canEditJobs: boolean;
  canCloseJobs: boolean;
  canSetAtsKeywords: boolean;
  canConnectLinkedIn: boolean;

  // Applicant Review
  canViewAllApplicants: boolean;      // HR sees all with scores
  canViewAtsFiltered: boolean;        // Only ATS-matched
  canAddApplicantNotes: boolean;
  canChangeApplicantStatus: boolean;
  canScheduleInterviews: boolean;
  canViewActivityLog: boolean;

  // Team Management
  canAddEmployees: boolean;
  canAddManagers: boolean;
  canAddHR: boolean;
  canRemoveEmployees: boolean;
  canEditEmployeeDetails: boolean;

  // Reviews
  canCreateReviews: boolean;          // Managers can create
  canViewOwnReviews: boolean;         // Employees can view
  canViewTeamReviews: boolean;        // Managers can view team
  canViewAllReviews: boolean;         // HR can view all

  // Referrals
  canSubmitReferrals: boolean;
  canReviewReferrals: boolean;        // HR reviews
  canConvertReferralToApp: boolean;   // HR converts to application

  // Benefits
  canViewOwnBenefits: boolean;
  canEditBenefits: boolean;           // HR only
  canViewTeamBenefits: boolean;       // Managers

  // Settings & Admin
  canManageSettings: boolean;
  canManageTier: boolean;
  canViewAnalytics: boolean;

  // Payroll (Enterprise only)
  canAccessPayroll: boolean;
  canRunPayroll: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class PermissionsService {
  private readonly authService = inject(AuthService);
  private readonly orgService = inject(OrganizationService);

  /**
   * Get all permissions for current user
   */
  getPermissions(): Permission {
    const user = this.authService.getCurrentUser();
    const role = user?.role || this.getPrimaryRole(user?.roles);
    const tier = this.orgService.tier;

    return this.getPermissionsForRole(role as UserRole, tier);
  }

  /**
   * Get permissions for specific role and tier
   */
  getPermissionsForRole(role: UserRole | string, tier: OrganizationTier): Permission {
    const normalizedRole = this.normalizeRole(role);

    const basePermissions = this.getBasePermissionsByRole(normalizedRole);
    const tierPermissions = this.getTierPermissions(tier);

    // Combine: base role permissions + tier restrictions
    return {
      ...basePermissions,
      // Tier restrictions
      canViewAnalytics: basePermissions.canViewAnalytics && tierPermissions.hasAnalytics,
      canAccessPayroll: basePermissions.canAccessPayroll && tierPermissions.hasPayroll,
      canRunPayroll: basePermissions.canRunPayroll && tierPermissions.hasPayroll,
    };
  }

  /**
   * Check if current user has a specific permission
   */
  hasPermission(permission: keyof Permission): boolean {
    return this.getPermissions()[permission];
  }

  /**
   * Check if current user has role
   */
  hasRole(role: UserRole | string): boolean {
    const user = this.authService.getCurrentUser();
    if (!user) return false;

    const userRole = user.role || this.getPrimaryRole(user?.roles);
    return this.normalizeRole(userRole) === this.normalizeRole(role);
  }

  /**
   * Check if current user has any of the roles
   */
  hasAnyRole(...roles: (UserRole | string)[]): boolean {
    return roles.some(role => this.hasRole(role));
  }

  private getBasePermissionsByRole(role: UserRole): Permission {
    switch (role) {
      case UserRole.ADMIN:
      case UserRole.HR_MANAGER:
        return {
          // ADMIN/HR_MANAGER = Full HR access
          canPostJobs: true,
          canEditJobs: true,
          canCloseJobs: true,
          canSetAtsKeywords: true,
          canConnectLinkedIn: true,

          canViewAllApplicants: true,      // Sees all with ATS scores
          canViewAtsFiltered: false,       // N/A - sees all
          canAddApplicantNotes: true,
          canChangeApplicantStatus: true,
          canScheduleInterviews: true,
          canViewActivityLog: true,

          canAddEmployees: true,
          canAddManagers: true,
          canAddHR: true,
          canRemoveEmployees: true,
          canEditEmployeeDetails: true,

          canCreateReviews: false,         // Managers create, not HR
          canViewOwnReviews: true,
          canViewTeamReviews: true,
          canViewAllReviews: true,         // HR can see all reviews

          canSubmitReferrals: true,
          canReviewReferrals: true,
          canConvertReferralToApp: true,

          canViewOwnBenefits: true,
          canEditBenefits: true,
          canViewTeamBenefits: true,

          canManageSettings: true,
          canManageTier: true,
          canViewAnalytics: true,

          canAccessPayroll: true,          // If Enterprise tier
          canRunPayroll: true,
        };

      case UserRole.MANAGER:
        return {
          canPostJobs: false,
          canEditJobs: false,
          canCloseJobs: false,
          canSetAtsKeywords: false,
          canConnectLinkedIn: false,

          canViewAllApplicants: false,
          canViewAtsFiltered: true,        // Can view dept applicants if granted
          canAddApplicantNotes: true,      // For their dept
          canChangeApplicantStatus: false, // HR controls
          canScheduleInterviews: true,     // For their dept
          canViewActivityLog: true,

          canAddEmployees: false,          // HR only
          canAddManagers: false,
          canAddHR: false,
          canRemoveEmployees: false,
          canEditEmployeeDetails: false,

          canCreateReviews: true,          // Main manager capability!
          canViewOwnReviews: true,
          canViewTeamReviews: true,        // Can see direct reports
          canViewAllReviews: false,

          canSubmitReferrals: true,
          canReviewReferrals: false,
          canConvertReferralToApp: false,

          canViewOwnBenefits: true,
          canEditBenefits: false,
          canViewTeamBenefits: true,       // Can see team benefits

          canManageSettings: false,
          canManageTier: false,
          canViewAnalytics: false,

          canAccessPayroll: false,
          canRunPayroll: false,
        };

      case UserRole.EMPLOYEE:
        return {
          canPostJobs: false,
          canEditJobs: false,
          canCloseJobs: false,
          canSetAtsKeywords: false,
          canConnectLinkedIn: false,

          canViewAllApplicants: false,
          canViewAtsFiltered: false,
          canAddApplicantNotes: false,
          canChangeApplicantStatus: false,
          canScheduleInterviews: false,
          canViewActivityLog: false,

          canAddEmployees: false,
          canAddManagers: false,
          canAddHR: false,
          canRemoveEmployees: false,
          canEditEmployeeDetails: false,

          canCreateReviews: false,
          canViewOwnReviews: true,         // Can view own reviews
          canViewTeamReviews: false,
          canViewAllReviews: false,

          canSubmitReferrals: true,        // Employees can refer!
          canReviewReferrals: false,
          canConvertReferralToApp: false,

          canViewOwnBenefits: true,        // Main employee capability
          canEditBenefits: false,
          canViewTeamBenefits: false,

          canManageSettings: false,
          canManageTier: false,
          canViewAnalytics: false,

          canAccessPayroll: false,
          canRunPayroll: false,
        };

      default:
        return this.getNoPermissions();
    }
  }

  private getTierPermissions(tier: OrganizationTier) {
    return {
      hasAnalytics: tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE,
      hasPayroll: tier === OrganizationTier.ENTERPRISE,
      hasAIAts: tier === OrganizationTier.PROFESSIONAL || tier === OrganizationTier.ENTERPRISE,
    };
  }

  private normalizeRole(role: UserRole | string | undefined): UserRole {
    if (!role) return UserRole.APPLICANT;

    const upper = role.toString().toUpperCase();

    if (upper === 'ADMIN') return UserRole.ADMIN;
    if (upper === 'HR_MANAGER') return UserRole.HR_MANAGER;
    if (upper === 'MANAGER') return UserRole.MANAGER;
    if (upper === 'EMPLOYEE') return UserRole.EMPLOYEE;
    if (upper === 'APPLICANT') return UserRole.APPLICANT;

    return UserRole.APPLICANT;
  }

  private getPrimaryRole(roles: string[] | undefined): string {
    if (!roles || roles.length === 0) return UserRole.APPLICANT;

    // Priority: ADMIN/HR_MANAGER > MANAGER > EMPLOYEE > APPLICANT
    if (roles.some(r => ['ADMIN', 'HR_MANAGER'].includes(r.toUpperCase()))) {
      return UserRole.ADMIN;
    }
    if (roles.some(r => r.toUpperCase() === 'MANAGER')) {
      return UserRole.MANAGER;
    }
    if (roles.some(r => r.toUpperCase() === 'EMPLOYEE')) {
      return UserRole.EMPLOYEE;
    }

    return roles[0];
  }

  private getNoPermissions(): Permission {
    return {
      canPostJobs: false,
      canEditJobs: false,
      canCloseJobs: false,
      canSetAtsKeywords: false,
      canConnectLinkedIn: false,
      canViewAllApplicants: false,
      canViewAtsFiltered: false,
      canAddApplicantNotes: false,
      canChangeApplicantStatus: false,
      canScheduleInterviews: false,
      canViewActivityLog: false,
      canAddEmployees: false,
      canAddManagers: false,
      canAddHR: false,
      canRemoveEmployees: false,
      canEditEmployeeDetails: false,
      canCreateReviews: false,
      canViewOwnReviews: false,
      canViewTeamReviews: false,
      canViewAllReviews: false,
      canSubmitReferrals: false,
      canReviewReferrals: false,
      canConvertReferralToApp: false,
      canViewOwnBenefits: false,
      canEditBenefits: false,
      canViewTeamBenefits: false,
      canManageSettings: false,
      canManageTier: false,
      canViewAnalytics: false,
      canAccessPayroll: false,
      canRunPayroll: false,
    };
  }
}
