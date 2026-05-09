import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const TOKEN_KEY = 'auth_token';

export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const token = localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY);
  const authService = inject(AuthService);
  const router = inject(Router);

  const authReq = token ? req.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`,
    },
  }) : req;

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Token expired or invalid - auto logout
        console.warn('Received 401 Unauthorized - logging out');
        authService.logout();

        // Redirect to login
        const currentUrl = window.location.pathname;
        if (!currentUrl.includes('/login')) {
          router.navigate(['/login']);
        }
      }
      return throwError(() => error);
    })
  );
};
