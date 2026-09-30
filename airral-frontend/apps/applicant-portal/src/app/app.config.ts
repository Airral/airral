import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { appRoutes } from './app.routes';
import { redrawAfterResponseInterceptor } from '@airral/shared-api';
import { authTokenInterceptor, PORTAL_ID } from '@airral/shared-auth';

export const appConfig: ApplicationConfig = {
  providers: [
    // This bundle's identity, so guards never infer it from the URL.
    { provide: PORTAL_ID, useValue: 'applicant' as const },
    provideBrowserGlobalErrorListeners(),
    // A new page starts at the top; Back and Forward return to where you were.
    // Without it the router kept the last page's scroll, so arriving from the
    // sign-in form left onboarding 56px down with its banner under the nav.
    provideRouter(appRoutes, withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })),
    // Zoneless: a response redraws the pages that handle it (see the interceptor).
    provideHttpClient(withInterceptors([authTokenInterceptor, redrawAfterResponseInterceptor])),
  ],
};
