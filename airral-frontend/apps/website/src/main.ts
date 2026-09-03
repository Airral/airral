import { bootstrapApplication } from '@angular/platform-browser';
import { consumeAuthHandoffBeforeBootstrap } from '@airral/shared-auth';
import { appConfig } from './app/app.config';
import { App } from './app/app';

// Before bootstrap, so the Router never parses -- and then restores -- a URL
// fragment carrying the session token.
consumeAuthHandoffBeforeBootstrap();

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
