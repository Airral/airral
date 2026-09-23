import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';

/**
 * Asks for a password reset link.
 *
 * <p>The confirmation says the same thing whether or not the address has an
 * account -- the API answers identically either way, and so must this page, or it
 * becomes the lookup the API was careful not to be.
 */
@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './forgot-password.component.html',
  styleUrls: ['../reset-password/reset-password.component.css'],
})
export class ForgotPasswordComponent {
  private readonly authApi = inject(AuthApiService);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);

  email = '';
  working = false;
  sent = false;
  errorMessage = '';

  submit(): void {
    const email = this.email.trim();
    if (this.working) return;
    // Validated here, not by disabling the button: a disabled submit button
    // swallows Enter when it is pressed before the next render re-enables it.
    if (!email) {
      this.errorMessage = 'Enter the email you signed up with.';
      this.changeDetectorRef.markForCheck();
      return;
    }
    this.working = true;
    this.errorMessage = '';
    this.changeDetectorRef.markForCheck();

    this.authApi.requestPasswordReset(email).subscribe({
      next: () => {
        this.sent = true;
        this.working = false;
        // Zoneless: nothing repaints off an HTTP callback on its own.
        this.changeDetectorRef.markForCheck();
      },
      error: (error) => {
        this.working = false;
        this.errorMessage =
          error?.status === 429
            ? 'Too many attempts from this network. Please wait a few minutes and try again.'
            : error?.status === 400
              ? 'Enter a valid email address.'
              : 'We could not reach the server. Please try again.';
        this.changeDetectorRef.markForCheck();
      },
    });
  }
}
