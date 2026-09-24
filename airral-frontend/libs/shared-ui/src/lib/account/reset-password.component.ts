import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { EmailLinkService, emailLinkErrorMessage } from '@airral/shared-auth';
import { captureEmailLink } from './email-link-landing';

/** The same rule RegisterRequest and ResetPasswordRequest enforce on the server. */
const PASSWORD_RULE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

/**
 * Where a reset email's link lands.
 *
 * <p>The link is redeemed when the new password is submitted, not when the page
 * opens: the API only accepts a proof from a link followed in the last fifteen
 * minutes, so redeeming early would fail anyone who took a while to choose.
 */
@Component({
  selector: 'airral-reset-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reset-password.component.html',
  styleUrls: ['./account-pages.css'],
})
export class ResetPasswordComponent implements OnInit {
  private readonly authApi = inject(AuthApiService);
  private readonly emailLink = inject(EmailLinkService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly doc = inject(DOCUMENT);

  /** The link as it arrived, held in memory once it is off the address bar. */
  private linkUrl = '';
  private idToken = '';

  checking = true;
  hasLink = false;
  /** Asked for only when the link is opened on a device that did not request it. */
  needsEmail = false;
  email = '';
  password = '';
  confirmPassword = '';
  showPassword = false;
  working = false;
  done = false;
  errorMessage = '';

  async ngOnInit(): Promise<void> {
    this.linkUrl = captureEmailLink(this.doc);
    try {
      this.hasLink = await this.emailLink.isEmailLink(this.linkUrl);
    } catch {
      this.hasLink = false;
    }
    const remembered = this.emailLink.rememberedEmail();
    this.email = remembered ?? '';
    this.needsEmail = this.hasLink && !remembered;
    this.checking = false;
    this.cdr.markForCheck();
  }

  get passwordProblem(): string {
    if (!this.password) return '';
    if (!PASSWORD_RULE.test(this.password)) {
      return 'Use at least 8 characters with uppercase, lowercase, and a number.';
    }
    if (this.confirmPassword && this.confirmPassword !== this.password) {
      return 'The two passwords do not match.';
    }
    return '';
  }

  async submit(): Promise<void> {
    if (this.working || !this.hasLink) return;
    // Validated here rather than by disabling the button: a disabled submit
    // button swallows Enter pressed straight after the last keystroke.
    if (this.needsEmail && !this.email.trim()) {
      return this.fail('Enter the email address the link was sent to.');
    }
    if (!PASSWORD_RULE.test(this.password)) {
      return this.fail('Use at least 8 characters with uppercase, lowercase, and a number.');
    }
    if (this.password !== this.confirmPassword) {
      return this.fail('The two passwords do not match.');
    }
    this.working = true;
    this.errorMessage = '';
    this.cdr.markForCheck();
    try {
      if (!this.idToken) {
        this.idToken = await this.emailLink.redeem(this.email, this.linkUrl);
      }
    } catch (error) {
      this.working = false;
      return this.fail(emailLinkErrorMessage(error));
    }
    this.authApi.resetPassword(this.idToken, this.password).subscribe({
      next: () => {
        this.done = true;
        this.working = false;
        this.password = '';
        this.confirmPassword = '';
        void this.emailLink.discard();
        this.cdr.markForCheck();
      },
      error: (error) => {
        this.working = false;
        this.fail(
          error?.status === 429
            ? 'Too many attempts from this network. Please wait a few minutes and try again.'
            : error?.status === 400
              ? (error?.error?.message ?? 'This reset link is invalid or has expired. Request a new one.')
              : 'We could not reach the server. Your password has not changed — please try again.'
        );
      },
    });
  }

  private fail(message: string): void {
    this.errorMessage = message;
    this.cdr.markForCheck();
  }
}
