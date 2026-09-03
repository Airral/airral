import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import {
  AuthService,
  PORTAL_ID,
  routeAfterAuth,
  userFromAuthResponse,
} from '@airral/shared-auth';
import { AuthResponse, User } from '@airral/shared-types';
import { PORTAL_ROUTES, USER_ROLES } from '@airral/shared-utils';

@Component({
  selector: 'app-hr-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly authApi = inject(AuthApiService);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly portal = inject(PORTAL_ID, { optional: true });

  email = '';
  password = '';
  isLoading = false;
  errorMessage = '';
  readonly portalRoutes = PORTAL_ROUTES;

  constructor() {
    if (this.authService.isAuthenticated()) {
      this.router.navigateByUrl('/');
    }
  }

  onSubmit(): void {
    if (!this.email || !this.password || this.isLoading) {
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    this.authApi.login({ email: this.email, password: this.password }).subscribe({
      next: (response) => this.handleAuthSuccess(response),
      error: () => {
        this.errorMessage = 'Unable to sign in. Check your credentials and try again.';
        this.isLoading = false;
      },
    });
  }

  private handleAuthSuccess(response: AuthResponse): void {
    const role = response.role || USER_ROLES.APPLICANT;
    const email = response.email || response.userEmail || this.email;
    const user = userFromAuthResponse(response, { email });

    this.isLoading = false;
    this.redirectByRole(role, user, response.token);
  }

  private redirectByRole(role: string, user: User, token: string): void {
    // Shared with the applicant login so the two doors cannot drift apart:
    // whichever one you arrive at, your role decides where you end up.
    routeAfterAuth({
      role,
      currentPortal: this.portal,
      user,
      token,
      router: this.router,
      authService: this.authService,
      returnUrl: this.route.snapshot.queryParamMap.get('returnUrl'),
    });
  }
}
