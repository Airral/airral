import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { RegisterRequest, User } from '@airral/shared-types';
import { FooterComponent, HeaderComponent, HeaderCta, HeaderNavLink } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-sign-up',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, RouterLink, HeaderComponent, FooterComponent],
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
        const user: User = {
          id: res.userId ?? 0,
          email: res.email || this.workEmail,
          firstName: res.firstName || firstName,
          lastName: res.lastName || last.join(' '),
          phone: this.phone,
          roles: [role],
          role,
          isActive: true,
        };

        this.authService.login(user, res.token);
        this.isLoading = false;
        window.location.href = PORTAL_ROUTES.HR;
      },
      error: () => {
        this.errorMessage = 'Unable to create employer account right now. Please try again.';
        this.isLoading = false;
      },
    });
  }
}
