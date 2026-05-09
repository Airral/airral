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
  selector: 'app-apply',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, RouterLink, HeaderComponent, FooterComponent],
  templateUrl: './apply.component.html',
  styleUrl: './apply.component.css',
})
export class ApplyComponent {
  firstName = '';
  lastName = '';
  email = '';
  phone = '';
  password = '';
  confirmPassword = '';
  isLoading = false;
  errorMessage = '';

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  constructor(
    private readonly authApi: AuthApiService,
    private readonly authService: AuthService
  ) {}

  onSubmit(): void {
    if (!this.isFormValid() || this.isLoading) {
      return;
    }

    if (this.password !== this.confirmPassword) {
      this.errorMessage = 'Passwords do not match.';
      return;
    }

    this.errorMessage = '';
    this.isLoading = true;

    const payload: RegisterRequest = {
      email: this.email,
      password: this.password,
      firstName: this.firstName,
      lastName: this.lastName,
      phone: this.phone,
    };

    this.authApi.register(payload).subscribe({
      next: (res) => {
        const role = res.role || 'APPLICANT';
        const user: User = {
          id: res.userId ?? 0,
          email: res.email || this.email,
          firstName: res.firstName || this.firstName,
          lastName: res.lastName || this.lastName,
          phone: this.phone,
          roles: [role],
          role,
          isActive: true,
        };

        this.authService.login(user, res.token);
        this.isLoading = false;
        window.location.href = PORTAL_ROUTES.APPLICANT;
      },
      error: () => {
        this.errorMessage = 'Unable to create your account right now. Please try again.';
        this.isLoading = false;
      },
    });
  }

  private isFormValid(): boolean {
    return !!this.firstName && !!this.lastName && !!this.email && !!this.password && !!this.confirmPassword;
  }
}
