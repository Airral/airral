import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthApiService } from '@airral/shared-api';
import { EmailLinkService } from '@airral/shared-auth';

/**
 * Ask for a password reset link.
 *
 * <p>AIRRAL's API checks the address and has Firebase send a link only if it
 * belongs to an account. It answers the same way, after the same short wait,
 * either way, so this page shows "Checking…" and then one confirmation for
 * everyone: nothing on screen says whether the address has an account.
 */
@Component({
  selector: 'airral-forgot-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forgot-password.component.html',
  styleUrls: ['./account-pages.css'],
})
export class ForgotPasswordComponent {
  private readonly authApi = inject(AuthApiService);
  private readonly emailLink = inject(EmailLinkService);
  private readonly cdr = inject(ChangeDetectorRef);

  email = '';
  working = false;
  sent = false;
  errorMessage = '';

  async submit(): Promise<void> {
    if (this.working) return;
    const email = this.email.trim();
    if (!email) {
      this.errorMessage = 'Enter the email you signed up with.';
      this.cdr.markForCheck();
      return;
    }
    this.working = true;
    this.errorMessage = '';
    this.cdr.markForCheck();
    try {
      await firstValueFrom(this.authApi.forgotPassword(email));
      // So the reset page, opened from the email on this device, need not ask.
      this.emailLink.rememberEmail(email);
      this.sent = true;
    } catch (error) {
      // Only ever about this device or this address being too busy -- never
      // about whether an account exists, which the API does not say.
      const status = (error as { status?: number })?.status;
      this.errorMessage = status === 429
        ? 'Too many requests. Wait a few minutes, then try again.'
        : status === 400
          ? 'Enter a valid email address.'
          : 'Could not reach AIRRAL. Check your connection and try again.';
    } finally {
      this.working = false;
      // Zoneless: nothing repaints off an awaited promise on its own.
      this.cdr.markForCheck();
    }
  }
}
