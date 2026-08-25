import { Route } from '@angular/router';
import { authGuard, roleGuard } from '@airral/shared-auth';

export const appRoutes: Route[] = [
  {
    path: '',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['ADMIN'] },
    loadComponent: () =>
      import('./pages/users/users.component').then((m) => m.UsersComponent),
  },
  {
    path: 'statistics',
    loadComponent: () =>
      import('./pages/statistics/public-statistics.component').then((m) => m.PublicStatisticsComponent),
  },
  { path: '**', redirectTo: '' },
];
