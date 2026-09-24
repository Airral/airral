import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EmailLinkService, emailLinkErrorMessage } from '@airral/shared-auth';

/**
 * Ask for a password reset link.
 *
 * <p>The link is sent by Firebase, straight from this page, to whatever address
 * is typed. AIRRAL's API is not asked anything here, so nothing about the answer
 * can reveal whether an account exists -- and the confirmation below says the
 * same thing either way.
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
      await this.emailLink.sendLink(email, '/reset-password');
      this.sent = true;
    } catch (error) {
      this.errorMessage = emailLinkErrorMessage(error);
    } finally {
      this.working = false;
      // Zoneless: nothing repaints off an awaited promise on its own.
      this.cdr.markForCheck();
    }
  }
}
