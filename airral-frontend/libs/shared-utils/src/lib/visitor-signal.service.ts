import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { API_BASE_URL } from './constants';

/**
 * Tells the backend a visit happened, and takes an email address.
 *
 * <p>Same-origin rather than a third-party tag. The audience here is largely
 * software engineers, who block analytics scripts at a rate that would make the
 * numbers confidently wrong rather than merely absent, and a first-party request
 * is not blocked. Nothing is written to the browser and no address is stored, so
 * there is no consent banner to bolt onto a site whose pitch is a lack of
 * friction.
 */
@Injectable({ providedIn: 'root' })
export class VisitorSignalService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private started = false;

  /**
   * Records a page view on every completed navigation.
   *
   * <p>Call once, from the root component. Safe to call again -- a second call
   * does nothing rather than double-counting every visit thereafter.
   */
  trackPageViews(app: string): void {
    if (this.started || typeof window === 'undefined') {
      return;
    }
    this.started = true;

    // No initial send. Angular emits NavigationEnd for the first navigation too,
    // so firing here as well counted every visit's first page twice -- which
    // would have inflated the one number this exists to report.
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => this.send('page_view', event.urlAfterRedirects, app));
  }

  /** Records something a visitor did, beyond arriving. */
  track(event: string, path?: string, app?: string): void {
    this.send(event, path ?? (typeof window !== 'undefined' ? window.location.pathname : ''), app);
  }

  /** Stores an address for someone not ready to make an account. */
  captureEmail(email: string, source: string) {
    return this.http.post<{ status: string; message: string }>(
      `${API_BASE_URL}/email-signups`,
      { email, source, referrer: this.referrer() }
    );
  }

  /**
   * Fire and forget, deliberately.
   *
   * <p>A statistic must never be something the visitor notices. Errors are
   * swallowed: if the beacon fails the page carries on, because the person did
   * not ask for this and could do nothing about it.
   */
  private send(event: string, path: string, app?: string): void {
    if (typeof window === 'undefined') {
      return;
    }

    this.http
      .post(`${API_BASE_URL}/events`, {
        event,
        path,
        app: app ?? 'website',
        referrer: this.referrer(),
      })
      .subscribe({ next: () => undefined, error: () => undefined });
  }

  /** Only the first referrer of the visit is interesting -- after that it is us. */
  private referrer(): string | undefined {
    if (typeof document === 'undefined' || !document.referrer) {
      return undefined;
    }
    try {
      const host = new URL(document.referrer).host;
      return host && host !== window.location.host ? document.referrer : undefined;
    } catch {
      return undefined;
    }
  }
}
