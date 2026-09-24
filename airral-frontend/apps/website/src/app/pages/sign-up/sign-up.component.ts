import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { AuthService, EmailLinkService, routeAfterAuth, sessionExpiryFromResponse, userFromAuthResponse } from '@airral/shared-auth';
import { RegisterRequest } from '@airral/shared-types';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-sign-up',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent, FooterComponent],
  templateUrl: './sign-up.component.html',
  styleUrl: './sign-up.component.css',
})
export class SignUpComponent {
  companyName = '';
  fullName = '';
  workEmail = '';
  phone = '';
  password = '';
  isLoading = false;
  errorMessage = '';

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  constructor(
    private readonly authApi: AuthApiService,
    private readonly authService: AuthService,
    private readonly router: Router,
    private readonly emailLink: EmailLinkService
  ) {}

  onSubmit(): void {
    if (!this.companyName || !this.fullName || !this.workEmail || !this.password || this.isLoading) {
      return;
    }

    this.errorMessage = '';
    this.isLoading = true;

    const [firstName, ...last] = this.fullName.trim().split(' ');
    const emailDomain = this.workEmail.includes('@') ? this.workEmail.split('@')[1] : undefined;
    const payload: RegisterRequest = {
      email: this.workEmail,
      password: this.password,
      firstName,
      lastName: last.join(' '),
      phone: this.phone,
      companyName: this.companyName,
      companyDomain: emailDomain,
    };

    this.authApi.register(payload).subscribe({
      next: async (res) => {
        // Send the verification link before leaving: routeAfterAuth hands off
        // to the HR portal with a full page load, which would abort a request
        // still in flight. Capped so a slow network cannot hang sign-up; if it
        // does not go out, the HR portal's banner has "Resend link". The link
        // lands on the HR portal, where this person is signed in after the
        // handoff, so the page knows the address without asking.
        await Promise.race([
          this.emailLink.sendLink(this.workEmail, '/verify-email', PORTAL_ROUTES.HR).catch(() => undefined),
          new Promise((resolve) => setTimeout(resolve, 4000)),
        ]);
        const user = userFromAuthResponse(res, {
          email: this.workEmail,
          phone: this.phone,
        });

        this.isLoading = false;

        // The marketing site never serves a signed-in user, so it stores no
        // session -- routeAfterAuth hands this one to the portal the role
        // belongs to. Going through it rather than hardcoding the HR portal
        // also means an account that comes back as an applicant lands
        // somewhere that works, instead of on a portal that will bounce it.
        routeAfterAuth({
          role: res.role,
          currentPortal: 'website',
          user,
          token: res.token,
          expiry: sessionExpiryFromResponse(res.expiresInSeconds),
          router: this.router,
          authService: this.authService,
        });
      },
      error: (error) => {
        // A 409 carries a message worth showing as-is: "Email already
        // registered", or that the company is already on AIRRAL.
        this.errorMessage = error?.status === 409 && error?.error?.message
          ? error.error.message
          : 'Unable to create employer account right now. Please try again.';
        this.isLoading = false;
      },
    });
  }
}
