import { Route } from '@angular/router';
import { authGuard, roleGuard } from '@airral/shared-auth';

export const appRoutes: Route[] = [
  {
    path: '',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['APPLICANT', 'ADMIN'] },
    loadComponent: () =>
      import('./pages/candidate-dashboard/candidate-dashboard.component').then((m) => m.CandidateDashboardComponent),
  },
  { path: '**', redirectTo: '' },
];
