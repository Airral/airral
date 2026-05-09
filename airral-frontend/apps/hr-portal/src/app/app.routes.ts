import { Route } from '@angular/router';
import { authGuard, roleGuard } from '@airral/shared-auth';
import { ROUTE_ACCESS } from './feature-config';

const internalAccess = {
  canActivate: [authGuard, roleGuard],
  data: { roles: ROUTE_ACCESS.internal },
};

const hrAccess = {
  canActivate: [authGuard, roleGuard],
  data: { roles: ROUTE_ACCESS.hr },
};

const managerAccess = {
  canActivate: [authGuard, roleGuard],
  data: { roles: ROUTE_ACCESS.manager },
};

const employeeAccess = {
  canActivate: [authGuard, roleGuard],
  data: { roles: ROUTE_ACCESS.employee },
};

export const appRoutes: Route[] = [
  {
    path: 'login',
    loadComponent: () =>
      import('./pages/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    ...internalAccess,
    loadComponent: () =>
      import('./pages/workspace-home/workspace-home.component').then(
        (m) => m.WorkspaceHomeComponent
      ),
  },
  {
    path: 'dashboard',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },
  {
    path: 'hire',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/hire-tool/hire-tool.component').then((m) => m.HireToolComponent),
  },
  {
    path: 'jobs',
    ...internalAccess,
    loadComponent: () => import('./pages/jobs/jobs.component').then((m) => m.JobsComponent),
  },
  {
    path: 'offers',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/offers/offers.component').then((m) => m.OffersComponent),
  },
  {
    path: 'candidates',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/candidates/candidates.component').then((m) => m.CandidatesComponent),
  },
  {
    path: 'interviews',
    ...managerAccess,
    loadComponent: () =>
      import('./pages/interviews/interviews.component').then((m) => m.InterviewsComponent),
  },
  {
    path: 'interviews/scorecard',
    ...internalAccess,
    loadComponent: () =>
      import('./pages/interview-scorecard/interview-scorecard.component').then((m) => m.InterviewScorecardComponent),
  },
  {
    path: 'analytics',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/analytics/analytics.component').then((m) => m.AnalyticsComponent),
  },
  {
    path: 'settings',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/settings/settings.component').then((m) => m.SettingsComponent),
  },
  {
    path: 'settings/integrations',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/settings/integrations/integrations.component').then((m) => m.IntegrationsComponent),
  },
  {
    path: 'settings/hiring-stages',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/settings/hiring-stages/hiring-stages.component').then((m) => m.HiringStagesComponent),
  },
  {
    path: 'settings/interview-kits',
    ...hrAccess,
    loadComponent: () =>
      import('./pages/settings/interview-kits/interview-kits.component').then((m) => m.InterviewKitsComponent),
  },
  {
    path: 'team-review',
    ...managerAccess,
    loadComponent: () =>
      import('./pages/team-review/team-review.component').then((m) => m.TeamReviewComponent),
  },
  {
    path: 'referrals',
    ...employeeAccess,
    loadComponent: () =>
      import('./pages/referrals/referrals.component').then((m) => m.ReferralsComponent),
  },
  {
    path: 'profile',
    ...internalAccess,
    loadComponent: () =>
      import('./pages/profile/profile.component').then((m) => m.ProfileComponent),
  },
  {
    path: 'benefits',
    ...internalAccess,
    loadComponent: () =>
      import('./pages/benefits/benefits.component').then((m) => m.BenefitsComponent),
  },
  { path: '**', redirectTo: '' },
];
