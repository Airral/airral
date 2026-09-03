import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthApiService } from '@airral/shared-api';
import {
  AuthService,
  buildLocalAuthHandoffUrl,
  userFromAuthResponse,
} from '@airral/shared-auth';
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
    private readonly authService: AuthService
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
      next: (res) => {
        const role = res.role || 'HR_MANAGER';
        const user = userFromAuthResponse(res, {
          email: this.workEmail,
          phone: this.phone,
        });

        this.authService.login(user, res.token);
        this.isLoading = false;
        window.location.href = buildLocalAuthHandoffUrl(PORTAL_ROUTES.HR, user, res.token);
      },
      error: () => {
        this.errorMessage = 'Unable to create employer account right now. Please try again.';
        this.isLoading = false;
      },
    });
  }
}
