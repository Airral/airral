import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const TOKEN_KEY = 'auth_token';

function parseRequestPath(url: string): string {
  try {
    const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
    return new URL(url, origin).pathname;
  } catch {
    return url;
  }
}

function getStoredToken(): string | null {
  try {
    const localToken = typeof localStorage !== 'undefined' ? localStorage.getItem(TOKEN_KEY) : null;
    const sessionToken = typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(TOKEN_KEY) : null;
    return localToken ?? sessionToken;
  } catch {
    return null;
  }
}

function isPublicReadRequest(url: string, method: string): boolean {
  if (method.toUpperCase() !== 'GET') {
    return false;
  }

  const path = parseRequestPath(url);
  return (
    path === '/api/feed/news' ||
    path === '/api/feed/signals' ||
    path === '/api/jobs/open' ||
    /^\/api\/jobs\/\d+$/.test(path) ||
    path.startsWith('/api/candidate/jobs/')
  );
}

function isAuthEndpoint(url: string): boolean {
  const path = parseRequestPath(url);
  return path.startsWith('/api/auth/');
}

function isEncryptedBackendToken(token: string): boolean {
  const parts = token.split('.');
  if (parts.length !== 5) {
    return false;
  }

  try {
    const header = JSON.parse(base64UrlDecode(parts[0]));
    return header?.alg === 'dir' && header?.enc === 'A256GCM';
  } catch {
    return false;
  }
}

function base64UrlDecode(value: string): string {
  const normalized = value
    .replace(/-/g, '+')
    .replace(/_/g, '/')
    .padEnd(Math.ceil(value.length / 4) * 4, '=');

  if (typeof atob !== 'undefined') {
    return atob(normalized);
  }

  const buffer = (globalThis as unknown as {
    Buffer?: { from(value: string, encoding: string): { toString(encoding: string): string } };
  }).Buffer;
  if (!buffer) {
    throw new Error('No base64 decoder is available.');
  }

  return buffer.from(normalized, 'base64').toString('binary');
}

export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const token = getStoredToken();
  const authService = inject(AuthService);
  const router = inject(Router);

  const shouldAttachToken =
    Boolean(token) &&
    isEncryptedBackendToken(token as string) &&
    !isAuthEndpoint(req.url) &&
    !isPublicReadRequest(req.url, req.method);

  const authReq = shouldAttachToken ? req.clone({
    setHeaders: {
      Authorization: `Bearer ${token as string}`,
    },
  }) : req;

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Token expired or invalid - auto logout
        console.warn('Received 401 Unauthorized - logging out');
        authService.logout();

        // Redirect to login
        const currentUrl = typeof window !== 'undefined' ? window.location.pathname : '';
        if (!currentUrl.includes('/login')) {
          router.navigate(['/login']);
        }
      }
      return throwError(() => error);
    })
  );
};
