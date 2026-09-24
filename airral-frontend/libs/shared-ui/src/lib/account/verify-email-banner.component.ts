import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AccountStatus, AuthApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { Subscription, firstValueFrom } from 'rxjs';

const RESEND_COOLDOWN_SECONDS = 60;

/**
 * "Check your inbox" for a signed-in account that has not proven its address, and
 * "your jobs are not visible yet" for an employer whose company is not verified.
 *
 * <p>Reads the account from the API on load rather than trusting the stored
 * session: a person who verified on their phone should not keep seeing this on
 * their laptop until they sign in again.
 */
@Component({
  selector: 'airral-verify-email-banner',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './verify-email-banner.component.html',
  styleUrls: ['./verify-email-banner.component.css'],
})
export class VerifyEmailBannerComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly authApi = inject(AuthApiService);
  private readonly cdr = inject(ChangeDetectorRef);
  private userSub?: Subscription;
  private cooldownTimer?: ReturnType<typeof setInterval>;

  status: AccountStatus | null = null;
  sending = false;
  sentTo = '';
  cooldown = 0;
  errorMessage = '';

  ngOnInit(): void {
    // Follow the stored user as well as the status loaded below: verifying on
    // /verify-email patches the stored user, and the banner on that same page
    // must drop at once rather than keep the answer it fetched before.
    this.userSub = this.auth.currentUser$.subscribe((user) => {
      if (user?.emailVerified && this.status && !this.status.emailVerified) {
        this.status = { ...this.status, emailVerified: true };
      }
      this.cdr.markForCheck();
    });
    if (!this.auth.isAuthenticated()) return;
    this.authApi.me().subscribe({
      next: (status) => {
        this.status = status;
        if (status.emailVerified && this.auth.getCurrentUser()?.emailVerified === false) {
          this.auth.patchCurrentUser({ emailVerified: true });
        }
        this.cdr.markForCheck();
      },
      // Leave the banner to what the session says; a failed status call must
      // never block the page.
      error: () => this.cdr.markForCheck(),
    });
  }

  ngOnDestroy(): void {
    this.userSub?.unsubscribe();
    if (this.cooldownTimer) clearInterval(this.cooldownTimer);
  }

  get email(): string {
    return this.status?.email ?? this.auth.getCurrentUser()?.email ?? '';
  }

  get needsEmailVerification(): boolean {
    if (!this.auth.isAuthenticated()) return false;
    if (this.status) return !this.status.emailVerified;
    return this.auth.getCurrentUser()?.emailVerified === false;
  }

  get companyPending(): boolean {
    return !!this.status?.organizationId && this.status.emailVerified
      && this.status.organizationVerificationStatus === 'PENDING';
  }

  get companyRejected(): boolean {
    return !!this.status?.organizationId && this.status.organizationVerificationStatus === 'REJECTED';
  }

  async resend(): Promise<void> {
    if (this.sending || this.cooldown > 0 || !this.email) return;
    this.sending = true;
    this.errorMessage = '';
    this.cdr.markForCheck();
    try {
      // The API sends to the signed-in account's own address, not one typed here.
      const result = await firstValueFrom(this.authApi.sendVerification());
      if (result.alreadyVerified) {
        this.auth.patchCurrentUser({ emailVerified: true });
        if (this.status) this.status = { ...this.status, emailVerified: true };
      } else {
        this.sentTo = this.email;
        this.startCooldown();
      }
    } catch (error) {
      const failure = error as { status?: number; error?: { message?: string } };
      this.errorMessage = failure?.status === 429
        ? failure.error?.message || 'Several links were sent recently. Check spam, or try again in 15 minutes.'
        : 'Could not send the link. Try again in a moment.';
    } finally {
      this.sending = false;
      this.cdr.markForCheck();
    }
  }

  private startCooldown(): void {
    this.cooldown = RESEND_COOLDOWN_SECONDS;
    if (this.cooldownTimer) clearInterval(this.cooldownTimer);
    this.cooldownTimer = setInterval(() => {
      this.cooldown = Math.max(0, this.cooldown - 1);
      if (this.cooldown === 0 && this.cooldownTimer) clearInterval(this.cooldownTimer);
      // Zoneless: a timer callback repaints nothing unless told to.
      this.cdr.markForCheck();
    }, 1000);
  }
}
