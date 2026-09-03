import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { appRoutes } from './app.routes';
import { authTokenInterceptor, PORTAL_ID } from '@airral/shared-auth';

export const appConfig: ApplicationConfig = {
  providers: [
    // This bundle's identity, so guards never infer it from the URL.
    { provide: PORTAL_ID, useValue: 'applicant' as const },
    provideBrowserGlobalErrorListeners(),
    provideRouter(appRoutes),
    provideHttpClient(withInterceptors([authTokenInterceptor])),
  ],
};
