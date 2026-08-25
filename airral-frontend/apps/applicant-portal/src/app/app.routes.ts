import { Route } from '@angular/router';
import { authGuard, roleGuard } from '@airral/shared-auth';
import { onboardingGuard, onboardingPageGuard } from './guards/onboarding.guard';

const authenticatedRoutes: Route[] = [
  {
    path: 'jobs',
    loadComponent: () =>
      import('./pages/jobs/jobs.component').then((m) => m.JobsComponent),
  },
  {
    path: 'tracker',
    loadComponent: () =>
      import('./pages/tracker/tracker.component').then((m) => m.TrackerComponent),
  },
  {
    path: 'resume',
    loadComponent: () =>
      import('./pages/resume/resume.component').then((m) => m.ResumeComponent),
  },
  {
    path: 'profile',
    loadComponent: () =>
      import('./pages/profile/profile.component').then((m) => m.ProfileComponent),
  },
  { path: '', redirectTo: 'jobs', pathMatch: 'full' },
];

export const appRoutes: Route[] = [
  {
    path: 'login',
    loadComponent: () =>
      import('./pages/applicant-login/applicant-login.component').then((m) => m.ApplicantLoginComponent),
  },
  {
    path: 'onboarding',
    canActivate: [authGuard, roleGuard, onboardingPageGuard],
    data: { roles: ['APPLICANT', 'ADMIN'] },
    loadComponent: () =>
      import('./pages/onboarding/onboarding.component').then((m) => m.OnboardingComponent),
  },
  {
    path: '',
    canActivate: [authGuard, roleGuard, onboardingGuard],
    data: { roles: ['APPLICANT', 'ADMIN'] },
    children: authenticatedRoutes,
  },
  { path: '**', redirectTo: '' },
];
