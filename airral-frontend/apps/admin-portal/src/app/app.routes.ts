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
    // Same guard as the users screen: issuing credentials is admin-only, and
    // the endpoints behind it enforce that independently.
    path: 'api-keys',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['ADMIN'] },
    loadComponent: () =>
      import('./pages/api-keys/api-keys.component').then((m) => m.ApiKeysComponent),
  },
  {
    path: 'statistics',
    loadComponent: () =>
      import('./pages/statistics/public-statistics.component').then((m) => m.PublicStatisticsComponent),
  },
  { path: '**', redirectTo: '' },
];
