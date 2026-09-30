import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { appRoutes } from './app.routes';
import { redrawAfterResponseInterceptor } from '@airral/shared-api';
import { authTokenInterceptor, PORTAL_ID } from '@airral/shared-auth';

export const appConfig: ApplicationConfig = {
  providers: [
    // This bundle's identity, so guards never infer it from the URL.
    { provide: PORTAL_ID, useValue: 'website' as const },
    provideBrowserGlobalErrorListeners(),
    provideRouter(appRoutes),
    // Zoneless: a response redraws the pages that handle it (see the interceptor).
    provideHttpClient(withFetch(), withInterceptors([authTokenInterceptor, redrawAfterResponseInterceptor])),
    provideClientHydration(withEventReplay()),
  ],
};
