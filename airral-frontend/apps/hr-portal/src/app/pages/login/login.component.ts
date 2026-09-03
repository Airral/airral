import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import {
  AuthService,
  buildLocalAuthHandoffUrl,
  PORTAL_ID,
  safeReturnUrl,
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
    const user: User = {
      id: response.userId ?? 0,
      email,
      firstName: response.firstName,
      lastName: response.lastName,
      organizationId: response.organizationId,
      organizationName: response.organizationName,
      organizationTier: response.organizationTier,
      roles: [role],
      role,
      isActive: true,
    };

    this.authService.login(user, response.token);
    this.isLoading = false;
    this.redirectByRole(role, user, response.token);
  }

  private redirectByRole(role: string, user: User, token: string): void {
    const normalizedRole = role.toUpperCase();
    const belongsHere =
      normalizedRole === USER_ROLES.ADMIN ||
      normalizedRole === USER_ROLES.HR_MANAGER ||
      normalizedRole === USER_ROLES.MANAGER ||
      normalizedRole === USER_ROLES.EMPLOYEE;

    if (belongsHere && this.portal === 'hr') {
      // Already on this origin: authService.login() has stored the session, so
      // navigate in place. Handing off to our own absolute URL would reload the
      // whole app, put the token in the address bar and browser history for no
      // reason, and discard the returnUrl the guard came here with -- which is
      // why a deep link into the portal used to dump you on the dashboard.
      this.router.navigateByUrl(
        safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'))
      );
      return;
    }

    // A genuinely different origin, so the session has to travel with the URL.
    const target = belongsHere ? PORTAL_ROUTES.HR : PORTAL_ROUTES.APPLICANT;
    window.location.href = buildLocalAuthHandoffUrl(target, user, token);
  }
}
