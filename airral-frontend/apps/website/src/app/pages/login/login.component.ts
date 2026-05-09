import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { AuthResponse, User } from '@airral/shared-types';
import { FooterComponent, HeaderComponent, HeaderCta, HeaderNavLink } from '@airral/shared-ui';
import { PORTAL_ROUTES, USER_ROLES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, RouterLink, HeaderComponent, FooterComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  email = '';
  password = '';
  isLoading = false;
  errorMessage = '';

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly hrPortalUrl = `${PORTAL_ROUTES.HR}/login`;

  constructor(
    private readonly authApi: AuthApiService,
    private readonly authService: AuthService
  ) {}

  onSubmit(): void {
    if (!this.email || !this.password || this.isLoading) {
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    this.authApi.login({ email: this.email, password: this.password }).subscribe({
      next: (res) => this.handleAuthSuccess(res),
      error: () => {
        this.errorMessage = 'Unable to sign in right now. Please check your credentials and try again.';
        this.isLoading = false;
      },
    });
  }

  private handleAuthSuccess(response: AuthResponse): void {
    const role = response.role || 'APPLICANT';
    const normalizedRole = role.toUpperCase();

    // Website login is ONLY for applicants
    // HR users should use the HR Portal login page
    if (
      normalizedRole === USER_ROLES.ADMIN ||
      normalizedRole === USER_ROLES.HR_MANAGER ||
      normalizedRole === USER_ROLES.MANAGER ||
      normalizedRole === USER_ROLES.EMPLOYEE
    ) {
      this.errorMessage = 'HR users should sign in at the HR Portal. Please visit the employer login page.';
      this.isLoading = false;
      return;
    }

    // Only allow applicants
    const email = response.email || response.userEmail || this.email;
    const user: User = {
      id: response.userId ?? 0,
      email,
      firstName: response.firstName,
      lastName: response.lastName,
      roles: [role],
      role,
      isActive: true,
    };

    this.authService.login(user, response.token);
    this.isLoading = false;
    this.redirectToApplicantPortal();
  }

  private redirectToApplicantPortal(): void {

    window.location.href = PORTAL_ROUTES.APPLICANT;
  }
}
