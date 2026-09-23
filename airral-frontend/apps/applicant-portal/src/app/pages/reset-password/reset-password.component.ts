import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';

/** The same rule RegisterRequest and ResetPasswordRequest enforce on the server. */
const PASSWORD_RULE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

/**
 * Where the link in a reset email lands.
 *
 * <p>The token arrives in the URL fragment, not the query string, because a
 * fragment is never sent to any server -- it stays out of access logs, proxies
 * and Referer headers. It is read once and then removed from the address bar, so
 * it is not left in browser history or in a screenshot of the page.
 */
@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reset-password.component.html',
  styleUrls: ['./reset-password.component.css'],
})
export class ResetPasswordComponent implements OnInit, OnDestroy {
  private readonly authApi = inject(AuthApiService);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);

  token = '';
  password = '';
  confirmPassword = '';
  showPassword = false;
  working = false;
  done = false;
  errorMessage = '';

  // A second reset link pasted into a tab already showing this page changes only
  // the fragment, which does not reload the page or re-run ngOnInit -- the page
  // would keep saying the link was missing. Found while testing.
  private readonly onHashChange = () => this.readTokenFromFragment();

  ngOnInit(): void {
    this.readTokenFromFragment();
    if (typeof window !== 'undefined') {
      window.addEventListener('hashchange', this.onHashChange);
    }
  }

  ngOnDestroy(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener('hashchange', this.onHashChange);
    }
  }

  private readTokenFromFragment(): void {
    if (typeof window === 'undefined') return;
    const token = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('token') ?? '';
    if (token) {
      this.token = token;
      this.done = false;
      this.errorMessage = '';
      window.history.replaceState(null, '', window.location.pathname);
    }
    this.changeDetectorRef.markForCheck();
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

  submit(): void {
    if (this.working || !this.token) return;
    // Validated here rather than by disabling the button. A disabled submit
    // button swallows Enter silently when someone types the last character and
    // presses Enter before the next render has re-enabled it -- seen in testing.
    if (!PASSWORD_RULE.test(this.password)) {
      this.errorMessage = 'Use at least 8 characters with uppercase, lowercase, and a number.';
      this.changeDetectorRef.markForCheck();
      return;
    }
    if (this.password !== this.confirmPassword) {
      this.errorMessage = 'The two passwords do not match.';
      this.changeDetectorRef.markForCheck();
      return;
    }
    this.working = true;
    this.errorMessage = '';
    this.changeDetectorRef.markForCheck();

    this.authApi.resetPassword(this.token, this.password).subscribe({
      next: () => {
        this.done = true;
        this.working = false;
        this.password = '';
        this.confirmPassword = '';
        this.changeDetectorRef.markForCheck();
      },
      error: (error) => {
        this.working = false;
        this.errorMessage =
          error?.status === 429
            ? 'Too many attempts from this network. Please wait a few minutes and try again.'
            : error?.status === 400
              ? (error?.error?.message ?? 'This reset link is invalid or has expired. Request a new one.')
              : 'We could not reach the server. Your password has not changed — please try again.';
        this.changeDetectorRef.markForCheck();
      },
    });
  }
}
