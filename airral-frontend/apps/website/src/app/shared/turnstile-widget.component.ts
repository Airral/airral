import { Component, DOCUMENT, ElementRef, EventEmitter, OnDestroy, Output, afterNextRender, inject } from '@angular/core';

/** The part of Cloudflare's Turnstile script this page uses. */
interface TurnstileApi {
  render(element: HTMLElement, options: Record<string, unknown>): string;
  reset(widgetId?: string): void;
  remove(widgetId?: string): void;
}

type TurnstileWindow = Window & {
  turnstile?: TurnstileApi;
  AIRRAL_RUNTIME_CONFIG?: { turnstileSiteKey?: string };
};

const SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
let loading: Promise<TurnstileApi> | null = null;

/**
 * Cloudflare Turnstile's public site key, from runtime-config.js. Empty until
 * the keys exist (the check is then off here and in the API), and on the server.
 */
export function turnstileSiteKey(): string {
  if (typeof window === 'undefined') return '';
  return ((window as TurnstileWindow).AIRRAL_RUNTIME_CONFIG?.turnstileSiteKey ?? '').trim();
}

function loadTurnstile(doc: Document): Promise<TurnstileApi> {
  const win = doc.defaultView as TurnstileWindow | null;
  if (win?.turnstile) return Promise.resolve(win.turnstile);
  if (!loading) {
    loading = new Promise<TurnstileApi>((resolve, reject) => {
      const script = doc.createElement('script');
      script.src = SCRIPT_URL;
      script.async = true;
      script.onload = () => (win?.turnstile ? resolve(win.turnstile) : reject(new Error('Turnstile did not load')));
      script.onerror = () => {
        loading = null;
        reject(new Error('Turnstile did not load'));
      };
      doc.head.appendChild(script);
    });
  }
  return loading;
}

/**
 * The "you're not a robot" check on the employer sign-up form. Most people
 * see a tick and nothing else; a script gets no token, and the API refuses an
 * employer sign-up without one once the keys are set.
 *
 * Renders only in the browser, after the page has hydrated, and only when a
 * site key is configured. The host stays in the page either way, so the server
 * render and the browser's agree.
 */
@Component({
  selector: 'app-turnstile',
  standalone: true,
  template: '',
  styles: [':host { display: block; }'],
})
export class TurnstileWidgetComponent implements OnDestroy {
  /** A person's token for this sign-up; '' once it expires or the check fails. */
  @Output() readonly token = new EventEmitter<string>();
  /** The check could not load (a blocker, or a network that stops Cloudflare). */
  @Output() readonly unavailable = new EventEmitter<void>();

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly doc = inject(DOCUMENT);
  private api?: TurnstileApi;
  private widgetId?: string;
  private destroyed = false;

  constructor() {
    afterNextRender(() => {
      const siteKey = turnstileSiteKey();
      if (!siteKey) return;
      loadTurnstile(this.doc)
        .then((api) => {
          if (this.destroyed) return;
          this.api = api;
          this.widgetId = api.render(this.host.nativeElement, {
            sitekey: siteKey,
            action: 'employer-signup',
            callback: (token: string) => this.token.emit(token),
            'expired-callback': () => this.token.emit(''),
            'error-callback': () => this.token.emit(''),
          });
        })
        .catch(() => this.unavailable.emit());
    });
  }

  /** Tokens are single-use: after a refused sign-up, ask for a new one. */
  reset(): void {
    if (this.api && this.widgetId) this.api.reset(this.widgetId);
    this.token.emit('');
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    if (this.api && this.widgetId) this.api.remove(this.widgetId);
  }
}
