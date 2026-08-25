import { CommonModule, isPlatformBrowser } from '@angular/common';
import {
  AfterViewInit,
  Component,
  ElementRef,
  EventEmitter,
  Inject,
  Input,
  OnDestroy,
  OnInit,
  Output,
  PLATFORM_ID,
  ViewChild,
} from '@angular/core';

type GoogleButtonText = 'signin_with' | 'signup_with' | 'continue_with';
type GoogleButtonContext = 'signin' | 'signup' | 'use';

declare global {
  interface Window {
    AIRRAL_RUNTIME_CONFIG?: {
      googleClientId?: string;
    };
    google?: {
      accounts?: {
        id?: {
          initialize: (options: unknown) => void;
          renderButton: (parent: HTMLElement, options: unknown) => void;
        };
      };
    };
  }
}

let googleScriptPromise: Promise<void> | null = null;

@Component({
  selector: 'airral-google-auth-button',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="google-auth-shell">
      <div #buttonHost class="google-button-host" *ngIf="googleConfigured"></div>
      <button class="google-placeholder" type="button" disabled *ngIf="!googleConfigured">
        <svg class="google-logo" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" aria-hidden="true">
          <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
          <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
          <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
          <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.18 1.48-4.97 2.31-8.16 2.31-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
          <path fill="none" d="M0 0h48v48H0z"/>
        </svg>
        Continue with Google
      </button>
      <p class="google-status" *ngIf="statusMessage">{{ statusMessage }}</p>
    </div>
  `,
  styles: [`
    .google-auth-shell {
      display: grid;
      gap: 8px;
    }

    .google-button-host {
      min-height: 44px;
      display: flex;
      justify-content: center;
    }

    .google-placeholder {
      width: 100%;
      min-height: 44px;
      border: 1px solid #dadce0;
      border-radius: 999px;
      background: #ffffff;
      color: #3c4043;
      font: inherit;
      font-weight: 700;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      opacity: 0.72;
    }

    .google-placeholder span {
      width: 20px;
      height: 20px;
    }

    .google-logo {
      width: 20px;
      height: 20px;
      flex-shrink: 0;
    }

    .google-status {
      margin: 0;
      color: #667789;
      font-size: 0.82rem;
      line-height: 1.4;
      text-align: center;
    }
  `],
})
export class GoogleAuthButtonComponent implements OnInit, AfterViewInit, OnDestroy {
  @Input() text: GoogleButtonText = 'continue_with';
  @Input() context: GoogleButtonContext = 'use';
  @Input() disabled = false;
  @Output() credentialReceived = new EventEmitter<string>();
  @Output() unavailable = new EventEmitter<string>();
  @ViewChild('buttonHost') private buttonHost?: ElementRef<HTMLElement>;

  googleConfigured = false;
  statusMessage = '';
  private destroyed = false;
  private readonly isBrowser: boolean;

  constructor(@Inject(PLATFORM_ID) platformId: object) {
    this.isBrowser = isPlatformBrowser(platformId);
  }

  ngOnInit(): void {
    if (!this.isBrowser) {
      return;
    }
    const clientId = this.getGoogleClientId();
    this.googleConfigured = Boolean(clientId);
    if (!clientId) {
      this.statusMessage = 'Google sign-in is unavailable right now. Use email below.';
    }
  }

  ngAfterViewInit(): void {
    if (!this.isBrowser) {
      return;
    }

    const clientId = this.getGoogleClientId();

    if (!clientId) {
      this.unavailable.emit(this.statusMessage);
      return;
    }

    this.loadGoogleScript()
      .then(() => this.renderGoogleButton(clientId))
      .catch(() => {
        this.statusMessage = 'Google sign-in could not load. You can still use email and password.';
        this.unavailable.emit(this.statusMessage);
      });
  }

  ngOnDestroy(): void {
    this.destroyed = true;
  }

  private renderGoogleButton(clientId: string): void {
    if (this.destroyed || this.disabled || !this.buttonHost?.nativeElement || !window.google?.accounts?.id) {
      return;
    }

    window.google.accounts.id.initialize({
      client_id: clientId,
      callback: (response: { credential?: string }) => {
        if (response.credential) {
          this.credentialReceived.emit(response.credential);
        }
      },
      context: this.context,
      ux_mode: 'popup',
      auto_select: false,
    });

    window.google.accounts.id.renderButton(this.buttonHost.nativeElement, {
      type: 'standard',
      theme: 'outline',
      size: 'large',
      shape: 'pill',
      text: this.text,
      logo_alignment: 'left',
      width: Math.min(360, this.buttonHost.nativeElement.clientWidth || 320),
    });
  }

  private loadGoogleScript(): Promise<void> {
    if (window.google?.accounts?.id) {
      return Promise.resolve();
    }

    if (!googleScriptPromise) {
      googleScriptPromise = new Promise((resolve, reject) => {
        const existing = document.querySelector<HTMLScriptElement>('script[src="https://accounts.google.com/gsi/client"]');
        if (existing) {
          existing.addEventListener('load', () => resolve(), { once: true });
          existing.addEventListener('error', () => reject(), { once: true });
          return;
        }

        const script = document.createElement('script');
        script.src = 'https://accounts.google.com/gsi/client';
        script.async = true;
        script.defer = true;
        script.onload = () => resolve();
        script.onerror = () => reject();
        document.head.appendChild(script);
      });
    }

    return googleScriptPromise;
  }

  private getGoogleClientId(): string {
    const runtimeClientId = window.AIRRAL_RUNTIME_CONFIG?.googleClientId || '';
    const metaClientId = document
      .querySelector<HTMLMetaElement>('meta[name="airral-google-client-id"]')
      ?.content || '';
    let localClientId = '';
    try {
      localClientId = window.localStorage?.getItem('AIRRAL_GOOGLE_CLIENT_ID') || '';
    } catch {
      localClientId = '';
    }
    return (runtimeClientId || metaClientId || localClientId).trim();
  }
}
