import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule, DOCUMENT } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthApiService, InvitationPreview } from '@airral/shared-api';
import {
  AuthService,
  EmailLinkService,
  PORTAL_ID,
  emailLinkErrorMessage,
  routeAfterAuth,
  sessionExpiryFromResponse,
  userFromAuthResponse,
} from '@airral/shared-auth';
import { ROLE_LABELS } from '@airral/shared-utils';
import { captureEmailLink } from './email-link-landing';

/** The same rule AcceptInvitationRequest enforces on the server. */
const PASSWORD_RULE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,}$/;

/**
 * Where an invitation email's link lands: /accept-invitation/<token>.
 *
 * <p>The token in the path says which invitation; the Firebase link around it
 * proves the person here owns the address it was sent to. The link is
 * redeemed when the password is submitted, then the new account signs in.
 */
@Component({
  selector: 'airral-accept-invitation',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './accept-invitation.component.html',
  styleUrls: ['./account-pages.css'],
})
export class AcceptInvitationComponent implements OnInit {
  private readonly authApi = inject(AuthApiService);
  private readonly authService = inject(AuthService);
  private readonly emailLink = inject(EmailLinkService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly portal = inject(PORTAL_ID, { optional: true });
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly doc = inject(DOCUMENT);

  private token = '';
  /** The link as it arrived, held in memory once it is off the address bar. */
  private linkUrl = '';
  private idToken = '';

  checking = true;
  invitation: InvitationPreview | null = null;
  /** Why this invitation cannot be used, when it cannot. */
  unavailable = '';
  hasLink = false;
  firstName = '';
  lastName = '';
  password = '';
  confirmPassword = '';
  showPassword = false;
  working = false;
  errorMessage = '';

  get roleLabel(): string {
    return (ROLE_LABELS[this.invitation?.role ?? ''] ?? 'team member').toLowerCase();
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

  async ngOnInit(): Promise<void> {
    this.token = this.route.snapshot.paramMap.get('token') ?? '';
    this.linkUrl = captureEmailLink(this.doc);
    try {
      this.hasLink = await this.emailLink.isEmailLink(this.linkUrl);
    } catch {
      this.hasLink = false;
    }

    try {
      this.invitation = await firstValueFrom(this.authApi.getInvitation(this.token));
      this.firstName = this.invitation.firstName ?? '';
      this.lastName = this.invitation.lastName ?? '';
    } catch (error) {
      const status = (error as { status?: number })?.status;
      this.unavailable =
        status === 404
          ? 'This invitation has expired, was cancelled or was already used. Ask your company to send a new one.'
          : 'We could not load this invitation. Check your connection and reload the page.';
    }
    this.checking = false;
    this.cdr.markForCheck();
  }

  async submit(): Promise<void> {
    if (this.working || !this.invitation || !this.hasLink) return;
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
        this.idToken = await this.emailLink.redeem(this.invitation.email, this.linkUrl);
      }
    } catch (error) {
      this.working = false;
      return this.fail(emailLinkErrorMessage(error));
    }

    this.authApi
      .acceptInvitation(this.token, {
        idToken: this.idToken,
        password: this.password,
        firstName: this.firstName.trim() || undefined,
        lastName: this.lastName.trim() || undefined,
      })
      .subscribe({
        next: () => this.signIn(),
        error: (error) => {
          this.working = false;
          this.fail(
            error?.status === 429
              ? 'Too many attempts from this network. Wait a few minutes and try again.'
              : error?.status === 400 || error?.status === 409
                ? (error?.message || 'This invitation can no longer be used.')
                : 'We could not reach the server. Your account was not created. Try again.'
          );
        },
      });
  }

  /** The account exists now; sign it in with the password just chosen. */
  private signIn(): void {
    const email = this.invitation?.email ?? '';
    this.authApi.login({ email, password: this.password }).subscribe({
      next: (response) => {
        void this.emailLink.discard();
        routeAfterAuth({
          role: response.role,
          currentPortal: this.portal,
          user: userFromAuthResponse(response, { email }),
          token: response.token,
          expiry: sessionExpiryFromResponse(response.expiresInSeconds),
          router: this.router,
          authService: this.authService,
        });
      },
      error: () => {
        // Only the automatic sign-in failed; the account is there.
        void this.emailLink.discard();
        this.router.navigateByUrl('/login');
      },
    });
  }

  private fail(message: string): void {
    this.errorMessage = message;
    this.cdr.markForCheck();
  }
}
