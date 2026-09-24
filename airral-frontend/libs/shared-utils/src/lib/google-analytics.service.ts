import { Injectable, inject } from '@angular/core';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

/** AIRRAL's GA4 property, shared by www.airral.com and apply.airral.com. */
export const GA_MEASUREMENT_ID = 'G-8Y613RG1Z7';

const LIVE_HOSTS = ['airral.com', 'www.airral.com', 'apply.airral.com'];

/**
 * Pages a person reaches from an emailed link. Those links carry a one-time
 * code or a personal token in the address, so GA is never loaded on them -- not even with the code
 * stripped, because gtag reads the address itself for its automatic events.
 */
const PRIVATE_PATHS = ['/verify-email', '/reset-password', '/forgot-password', '/unsubscribe'];

/** Set to "1" in localStorage to try GA on localhost; hits then go to DebugView only. */
const DEBUG_FLAG = 'airral.ga.debug';

type Gtag = (...args: unknown[]) => void;

/**
 * Google Analytics, sending only what AIRRAL decides to send.
 *
 * <p>Traffic reports -- where people come from, which cities, phone or laptop
 * -- that the first-party VisitorSignalService does not try to answer. That one
 * stays the source of truth for the admin Launch page, because ad blockers hide
 * a share of GA hits.
 *
 * <p>Page views are sent by hand with the path only: no query string and no
 * fragment, which here can hold search terms, one-time codes or the cross-portal
 * session handoff. Google signals and ad personalisation are off, and a browser
 * asking not to be tracked (Do Not Track or Global Privacy Control) is not.
 */
@Injectable({ providedIn: 'root' })
export class GoogleAnalyticsService {
  private readonly router = inject(Router);
  private gtag: Gtag | null = null;
  private app = '';

  start(app: string): void {
    if (this.gtag || typeof window === 'undefined' || typeof document === 'undefined') return;

    const debug = readFlag();
    if (!debug && !LIVE_HOSTS.includes(window.location.hostname)) return;
    if (!debug && optedOut()) return;
    if (isPrivate(window.location.pathname)) return;

    this.app = app;
    const w = window as unknown as { dataLayer: unknown[]; gtag?: Gtag };
    w.dataLayer = w.dataLayer || [];
    // gtag.js reads the arguments object itself, not an array copy of it.
    // eslint-disable-next-line prefer-rest-params
    const gtag: Gtag = function () { w.dataLayer.push(arguments); };
    w.gtag = gtag;
    this.gtag = gtag;

    gtag('js', new Date());
    gtag('config', GA_MEASUREMENT_ID, {
      send_page_view: false,
      page_location: cleanLocation(window.location.pathname),
      allow_google_signals: false,
      allow_ad_personalization_signals: false,
      ...(debug ? { debug_mode: true } : {}),
    });

    const script = document.createElement('script');
    script.async = true;
    script.src = `https://www.googletagmanager.com/gtag/js?id=${GA_MEASUREMENT_ID}`;
    document.head.appendChild(script);

    // Angular emits NavigationEnd for the first navigation too, so this sends
    // the landing page as well as every page after it.
    this.router.events
      .pipe(filter((event): event is NavigationEnd => event instanceof NavigationEnd))
      .subscribe((event) => this.pageView(event.urlAfterRedirects));
  }

  /** A named event, e.g. apply_click. Does nothing if GA is not running. */
  event(name: string, params: Record<string, string | number> = {}): void {
    if (!this.gtag || isPrivate(window.location.pathname)) return;
    this.gtag('event', name, { app: this.app, ...params });
  }

  private pageView(url: string): void {
    if (!this.gtag) return;
    const path = url.split(/[?#]/)[0] || '/';
    if (isPrivate(path)) return;
    const location = cleanLocation(path);
    // Every later automatic event reads the page from here, not from the address bar.
    this.gtag('set', { page_location: location });
    this.gtag('event', 'page_view', {
      page_location: location,
      page_path: path,
      page_title: document.title,
      app: this.app,
    });
  }
}

function cleanLocation(path: string): string {
  return `${window.location.origin}${path}`;
}

function isPrivate(path: string): boolean {
  return PRIVATE_PATHS.some((p) => path === p || path.startsWith(`${p}/`));
}

function optedOut(): boolean {
  const nav = navigator as Navigator & { globalPrivacyControl?: boolean };
  return nav.globalPrivacyControl === true || nav.doNotTrack === '1';
}

function readFlag(): boolean {
  try {
    return window.localStorage.getItem(DEBUG_FLAG) === '1';
  } catch {
    return false;
  }
}
