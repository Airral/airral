import { ApplicationRef, inject } from '@angular/core';
import { HttpInterceptorFn } from '@angular/common/http';
import { finalize } from 'rxjs';

/**
 * Redraws the app once a request settles.
 *
 * The apps run without zone.js, so a response arriving does not by itself
 * redraw anything: a page that sets a plain field in its subscribe callback
 * keeps showing the old value until something else, such as a click, redraws
 * it. Signals and markForCheck do not have that problem; pages written before
 * the switch to zoneless do. Marking the root for check when the request is
 * done, after the page has handled the response or the error, redraws them.
 */
export const redrawAfterResponseInterceptor: HttpInterceptorFn = (request, next) => {
  const appRef = inject(ApplicationRef);
  return next(request).pipe(
    finalize(() => {
      for (const component of appRef.components) {
        component.changeDetectorRef.markForCheck();
      }
    }),
  );
};
