import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { AuthService, EmailLinkService, emailLinkErrorMessage } from '@airral/shared-auth';
import { captureEmailLink } from './email-link-landing';

/**
 * Where a sign-up verification email's link lands.
 *
 * <p>Works signed in or not: the link is often opened on a phone after signing up
 * on a laptop. The Firebase proof names the address, so AIRRAL needs nothing from
 * a session to know which account it is.
 */
@Component({
  selector: 'airral-verify-email',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './verify-email.component.html',
  styleUrls: ['./account-pages.css'],
})
export class VerifyEmailComponent implements OnInit {
  private readonly authApi = inject(AuthApiService);
  private readonly auth = inject(AuthService);
  private readonly emailLink = inject(EmailLinkService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly doc = inject(DOCUMENT);

  private linkUrl = '';

  state: 'checking' | 'needs-email' | 'working' | 'verified' | 'no-account' | 'error' | 'no-link' = 'checking';
  email = '';
  message = '';

  get signedIn(): boolean {
    return this.auth.isAuthenticated();
  }

  async ngOnInit(): Promise<void> {
    this.linkUrl = captureEmailLink(this.doc);
    let isLink = false;
    try {
      isLink = await this.emailLink.isEmailLink(this.linkUrl);
    } catch {
      isLink = false;
    }
    if (!isLink) {
      this.state = 'no-link';
      return this.refresh();
    }
    // Firebase needs the address again to complete a link. The device that asked
    // for it remembers it; a signed-in session knows it too; otherwise ask.
    this.email = this.emailLink.rememberedEmail() ?? this.auth.getCurrentUser()?.email ?? '';
    if (!this.email) {
      this.state = 'needs-email';
      return this.refresh();
    }
    await this.complete();
  }

  async complete(): Promise<void> {
    if (!this.email.trim()) {
      this.message = 'Enter the email address the link was sent to.';
      return this.refresh();
    }
    this.state = 'working';
    this.message = '';
    this.refresh();
    let idToken: string;
    try {
      idToken = await this.emailLink.redeem(this.email, this.linkUrl);
    } catch (error) {
      this.state = 'error';
      this.message = emailLinkErrorMessage(error);
      return this.refresh();
    }
    this.authApi.verifyEmail(idToken).subscribe({
      next: (result) => {
        this.state = result.verified ? 'verified' : 'no-account';
        this.message = result.message;
        if (result.verified && this.auth.getCurrentUser()?.email?.toLowerCase() === result.email) {
          this.auth.patchCurrentUser({ emailVerified: true });
        }
        void this.emailLink.discard();
        this.refresh();
      },
      error: (error) => {
        this.state = 'error';
        this.message = error?.error?.message ?? 'We could not verify your email right now. Please try again.';
        this.refresh();
      },
    });
  }

  private refresh(): void {
    // Zoneless: nothing repaints off an awaited promise or HTTP callback on its own.
    this.cdr.markForCheck();
  }
}
