import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

function parseRequestPath(url: string): string {
  try {
    const origin = typeof window !== 'undefined' ? window.location.origin : 'http://localhost';
    return new URL(url, origin).pathname;
  } catch {
    return url;
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
    /^\/api\/jobs\/\d+$/.test(path)
  );
}

/**
 * The auth endpoints a session is established or recovered through, which must
 * never be sent an existing token.
 *
 * <p>Was every path under /api/auth/, which also stripped the token from the two
 * that need one: /auth/me answered 401 and the handler below signed the person
 * out -- straight after sign-up. /auth/revoke-sessions had the same problem
 * waiting for the first button that calls it.
 */
const CREDENTIAL_ENDPOINTS = [
  '/api/auth/login',
  '/api/auth/register',
  '/api/auth/google',
  '/api/auth/forgot-password',
  '/api/auth/verify-email',
  '/api/auth/reset-password',
];

function isAuthEndpoint(url: string): boolean {
  const path = parseRequestPath(url);
  return CREDENTIAL_ENDPOINTS.some((endpoint) => path === endpoint || path.startsWith(endpoint + '/'));
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

/**
 * Routes that work without signing in, where a 401 must not redirect.
 *
 * <p>The handler below logs out and navigates to /login on any 401, which is
 * right on a page that needed a session and wrong on a page that did not. The
 * unsubscribe page is reached from an email footer by someone whose session is
 * usually long dead -- a weekly digest goes out on Sundays -- and the shell
 * fires an authenticated badge request on every route, so that 401 arrived
 * moments after the page rendered and replaced it with a sign-in form, with the
 * token gone from the URL. The reader was asked to log in in order to
 * unsubscribe, which is the dead end this page exists to remove.
 *
 * <p>The session is still cleared, because the token really is dead. Only the
 * navigation is suppressed. authGuard already redirects, and it preserves a
 * returnUrl, which this never did.
 */
const PUBLIC_PAGE_PATHS = ['/unsubscribe'];

function isOnPublicPage(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }
  const path = window.location.pathname;
  return PUBLIC_PAGE_PATHS.some((publicPath) => path === publicPath || path.startsWith(publicPath + '/'));
}

export const authTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const token = authService.getToken();

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

        // Redirect to login, unless the reader is on a page that never needed
        // one -- see PUBLIC_PAGE_PATHS above.
        const currentUrl = typeof window !== 'undefined' ? window.location.pathname : '';
        if (!currentUrl.includes('/login') && !isOnPublicPage()) {
          router.navigate(['/login']);
        }
      }
      return throwError(() => error);
    })
  );
};
