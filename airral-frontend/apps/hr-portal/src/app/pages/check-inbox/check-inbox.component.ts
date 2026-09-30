import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiError, AuthApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';

const RESEND_COOLDOWN_SECONDS = 60;

/**
 * Where a new company waits for its first HR manager to click the link in the
 * verification email. The workspace opens once they have.
 */
@Component({
  selector: 'app-check-inbox',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './check-inbox.component.html',
  styleUrl: './check-inbox.component.css',
})
export class CheckInboxComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly authApi = inject(AuthApiService);
  private readonly router = inject(Router);
  private timer?: ReturnType<typeof setInterval>;

  readonly email = this.auth.getCurrentUser()?.email ?? 'your email address';
  readonly checking = signal(false);
  readonly sending = signal(false);
  readonly cooldown = signal(0);
  readonly message = signal('');

  ngOnInit(): void {
    // Verified elsewhere already (the link opened on a phone, say): straight in.
    if (this.auth.getCurrentUser()?.emailVerified !== false) {
      void this.router.navigate(['/']);
    }
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  /** After clicking the link on another device, this page has not heard: ask the API. */
  checkAgain(): void {
    if (this.checking()) return;
    this.checking.set(true);
    this.message.set('');
    this.authApi.me().subscribe({
      next: (status) => {
        this.checking.set(false);
        if (status.emailVerified) {
          this.auth.patchCurrentUser({ emailVerified: true });
          void this.router.navigate(['/']);
        } else {
          this.message.set(`${this.email} is not verified yet. Open the email from AIRRAL and click its link.`);
        }
      },
      error: (error: ApiError) => {
        this.checking.set(false);
        this.message.set(error?.message || 'We could not check just now. Try again in a moment.');
      },
    });
  }

  resend(): void {
    if (this.sending() || this.cooldown() > 0) return;
    this.sending.set(true);
    this.message.set('');
    this.authApi.sendVerification().subscribe({
      next: (result) => {
        this.sending.set(false);
        if (result.alreadyVerified) {
          this.auth.patchCurrentUser({ emailVerified: true });
          void this.router.navigate(['/']);
          return;
        }
        this.message.set(`New link sent to ${this.email}. Check spam if it isn't there in a minute.`);
        this.startCooldown();
      },
      error: (error: ApiError) => {
        this.sending.set(false);
        this.message.set(error?.status === 429
          ? error.message || 'Several links were sent recently. Check spam, or try again in 15 minutes.'
          : 'We could not send the link. Try again in a moment.');
      },
    });
  }

  signOut(): void {
    this.auth.logout();
    window.location.href = '/login';
  }

  private startCooldown(): void {
    this.cooldown.set(RESEND_COOLDOWN_SECONDS);
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      const left = this.cooldown() - 1;
      this.cooldown.set(Math.max(0, left));
      if (left <= 0 && this.timer) clearInterval(this.timer);
    }, 1000);
  }
}
