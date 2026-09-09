import { Route } from '@angular/router';
import { authGuard, roleGuard } from '@airral/shared-auth';
import { onboardingGuard, onboardingPageGuard } from './guards/onboarding.guard';

const authenticatedRoutes: Route[] = [
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
  { path: '', redirectTo: '/jobs', pathMatch: 'full' },
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
    // Public on purpose. Someone arriving here came to look for work, and the
    // job list is the only thing on this site that shows what AIRRAL is for --
    // the analysis panel, the quality score, the salary. Putting a login in
    // front of it asked people to trust the product before seeing it, and the
    // corpus is public data anyway: the same search answers without a
    // credential, which is how every job in it was found in the first place.
    //
    // Saving, resume fit and resume health still need an account. Those are
    // prompted at the point they are used, where the ask has an obvious reason.
    path: 'jobs',
    // Public, but still onboarding-checked. onboardingGuard returns true when
    // there is no session, so this stays open to visitors; what it adds is the
    // signed-in case. A first-time user who lands here rather than on '/' used to
    // miss the onboarding redirect entirely, because this route sits outside the
    // guarded block and nothing else revisits the question.
    canActivate: [onboardingGuard],
    loadComponent: () =>
      import('./pages/jobs/jobs.component').then((m) => m.JobsComponent),
  },
  {
    path: '',
    canActivate: [authGuard, roleGuard, onboardingGuard],
    data: { roles: ['APPLICANT', 'ADMIN'] },
    children: authenticatedRoutes,
  },
  // An unknown path lands on the jobs list rather than a login form. Previously
  // this redirected to '', whose guard bounced a visitor straight to /login.
  { path: '**', redirectTo: 'jobs' },
];
