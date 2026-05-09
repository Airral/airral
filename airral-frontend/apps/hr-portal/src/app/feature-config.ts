export interface HrNavItem {
  label: string;
  path: string;
  icon: string;
  tierRequired?: 'PRO' | 'ENTERPRISE';
}

export type RoleSegment = 'HR' | 'MANAGER' | 'EMPLOYEE' | 'APPLICANT';

export const ROLE_GROUPS = {
  HR: ['ADMIN', 'HR_MANAGER'],
  MANAGER: ['MANAGER'],
  EMPLOYEE: ['EMPLOYEE'],
  APPLICANT: ['APPLICANT']
} as const;

export const ROUTE_ACCESS = {
  internal: [...ROLE_GROUPS.HR, ...ROLE_GROUPS.MANAGER, ...ROLE_GROUPS.EMPLOYEE],
  hr: [...ROLE_GROUPS.HR],
  manager: [...ROLE_GROUPS.MANAGER, ...ROLE_GROUPS.HR],
  employee: [...ROLE_GROUPS.EMPLOYEE, ...ROLE_GROUPS.HR],
} as const;

const NAV_BY_SEGMENT: Record<RoleSegment, HrNavItem[]> = {
  HR: [
    { label: 'Home', path: '/', icon: '▦' },
    { label: 'Dashboard', path: '/dashboard', icon: '◧' },
    { label: 'Pipeline', path: '/hire', icon: '◍', tierRequired: 'PRO' },
    { label: 'Jobs', path: '/jobs', icon: '💼' },
    { label: 'Candidates', path: '/candidates', icon: '👥' },
    { label: 'Interviews', path: '/interviews', icon: '📅' },
    { label: 'Offers', path: '/offers', icon: '📝' },
    { label: 'Analytics', path: '/analytics', icon: '◎', tierRequired: 'PRO' },
    { label: 'Settings', path: '/settings', icon: '◦' }
  ],
  MANAGER: [
    { label: 'Home', path: '/', icon: '▦' },
    { label: 'My Team Reviews', path: '/team-review', icon: '◉' },
    { label: 'Interviews', path: '/interviews', icon: '📅' },
    { label: 'Internal Jobs', path: '/jobs', icon: '💼' },
    { label: 'Referrals', path: '/referrals', icon: '🤝' },
    { label: 'My Profile', path: '/profile', icon: '👤' },
    { label: 'My Benefits', path: '/benefits', icon: '🎁' }
  ],
  EMPLOYEE: [
    { label: 'Home', path: '/', icon: '▦' },
    { label: 'Internal Jobs', path: '/jobs', icon: '💼' },
    { label: 'Referrals', path: '/referrals', icon: '🤝' },
    { label: 'My Profile', path: '/profile', icon: '👤' },
    { label: 'My Benefits', path: '/benefits', icon: '🎁' }
  ],
  APPLICANT: [{ label: 'Home', path: '/', icon: '▦' }],
};

const ROLE_LABEL_BY_SEGMENT: Record<RoleSegment, string> = {
  HR: 'HR View',
  MANAGER: 'Manager View',
  EMPLOYEE: 'Employee View',
  APPLICANT: 'Applicant View',
};

const SEARCH_LABEL_BY_SEGMENT: Record<RoleSegment, string> = {
  HR: 'Search candidates, jobs...',
  MANAGER: 'Search team, reviews, jobs...',
  EMPLOYEE: 'Search internal jobs, referrals...',
  APPLICANT: 'Search jobs...',
};

export function getPrimaryRole(roles: string[] | undefined): string {
  const primaryRole = roles?.[0]?.toUpperCase();

  if (primaryRole) {
    return primaryRole;
  }

  return 'APPLICANT';
}

export function getRoleSegment(role: string): RoleSegment {
  const normalized = role.toUpperCase();

  if (ROLE_GROUPS.HR.includes(normalized as (typeof ROLE_GROUPS.HR)[number])) {
    return 'HR';
  }

  if (ROLE_GROUPS.MANAGER.includes(normalized as (typeof ROLE_GROUPS.MANAGER)[number])) {
    return 'MANAGER';
  }

  if (ROLE_GROUPS.EMPLOYEE.includes(normalized as (typeof ROLE_GROUPS.EMPLOYEE)[number])) {
    return 'EMPLOYEE';
  }

  return 'APPLICANT';
}

export function getNavItemsForRole(role: string): HrNavItem[] {
  return NAV_BY_SEGMENT[getRoleSegment(role)];
}

export function getRoleLabelForRole(role: string): string {
  return ROLE_LABEL_BY_SEGMENT[getRoleSegment(role)];
}

export function getSearchLabelForRole(role: string): string {
  return SEARCH_LABEL_BY_SEGMENT[getRoleSegment(role)];
}

export function getWorkspaceTitleForRole(role: string): string {
  const segment = getRoleSegment(role);
  const titles: Record<RoleSegment, string> = {
    HR: 'HR Workspace',
    MANAGER: 'Manager Workspace',
    EMPLOYEE: 'Employee Workspace',
    APPLICANT: 'Workspace',
  };
  return titles[segment];
}

export function filterNavByTier(items: HrNavItem[], tier: import('@airral/shared-types').OrganizationTier): HrNavItem[] {
  // Enterprise tier gets everything
  if (tier === 'ENTERPRISE') return items;

  // Professional tier gets PRO features but not ENTERPRISE features
  if (tier === 'PROFESSIONAL') {
    return items.filter(item => item.tierRequired !== 'ENTERPRISE');
  }

  // Free tier (QUICK_HIRE) gets only non-tier-restricted features
  return items.filter(item => !item.tierRequired);
}
