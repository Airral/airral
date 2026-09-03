import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthApiService } from '@airral/shared-api';
import { AuthService, safeReturnUrl } from '@airral/shared-auth';
import { AuthResponse, RegisterRequest, User } from '@airral/shared-types';
import { USER_ROLES } from '@airral/shared-utils';
import { GoogleAuthButtonComponent } from '@airral/shared-ui';

type AuthMode = 'login' | 'register';

@Component({
  selector: 'app-applicant-login',
  standalone: true,
  imports: [CommonModule, FormsModule, GoogleAuthButtonComponent],
  templateUrl: './applicant-login.component.html',
  styleUrl: './applicant-login.component.css',
})
export class ApplicantLoginComponent {
  mode: AuthMode = 'login';
  email = '';
  password = '';
  firstName = '';
  lastName = '';
  loading = false;
  googleLoading = false;
  errorMessage = '';
  showPassword = false;
  googleAvailable = false;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly authApi: AuthApiService,
    private readonly authService: AuthService
  ) {
    this.mode = this.route.snapshot.queryParamMap.get('mode') === 'register' ? 'register' : 'login';
    this.googleAvailable = this.hasGoogleClientId();
  }

  get isRegisterMode(): boolean {
    return this.mode === 'register';
  }

  get passwordInputId(): string {
    return this.isRegisterMode ? 'new-password' : 'current-password';
  }

  setMode(mode: AuthMode): void {
    if (this.mode === mode || this.loading || this.googleLoading) {
      return;
    }

    this.mode = mode;
    this.errorMessage = '';
    this.password = '';
    this.firstName = '';
    this.lastName = '';
    this.showPassword = false;
  }

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  submit(): void {
    if (this.loading || this.googleLoading) {
      return;
    }

    if (this.isRegisterMode) {
      this.register();
      return;
    }

    this.login();
  }

  handleGoogleCredential(credential: string): void {
    if (this.loading || this.googleLoading) {
      return;
    }

    this.googleLoading = true;
    this.errorMessage = '';

    this.authApi.googleLogin({ credential }).subscribe({
      next: (response) => this.handleAuthSuccess(response),
      error: () => {
        this.errorMessage = 'Unable to continue with Google right now. Please use email and password.';
        this.googleLoading = false;
      },
    });
  }

  private login(): void {
    if (!this.email || !this.password) {
      this.errorMessage = 'Enter your email and password to sign in.';
      return;
    }

    if (!this.isEmailLike()) {
      this.errorMessage = 'Enter a valid email address.';
      return;
    }

    this.loading = true;
    this.errorMessage = '';

    this.authApi.login({ email: this.email.trim(), password: this.password }).subscribe({
      next: (response) => this.handleAuthSuccess(response),
      error: () => {
        this.errorMessage = 'Unable to sign in. Check the email and password, then try again.';
        this.loading = false;
      },
    });
  }

  private register(): void {
    if (!this.email || !this.password) {
      this.errorMessage = 'Enter an email and password to create your account.';
      return;
    }

    if (!this.isEmailLike()) {
      this.errorMessage = 'Enter a valid email address.';
      return;
    }

    if (!this.isRegisterPasswordStrong()) {
      this.errorMessage = 'Use at least 8 characters with uppercase, lowercase, and a number.';
      return;
    }

    this.loading = true;
    this.errorMessage = '';

    const payload: RegisterRequest = {
      email: this.email.trim(),
      password: this.password,
      firstName: this.firstName.trim() || undefined,
      lastName: this.lastName.trim() || undefined,
    };

    this.authApi.register(payload).subscribe({
      next: (response) => this.handleAuthSuccess(response),
      error: () => {
        this.errorMessage = 'Unable to create your account right now. Try again or sign in if you already have one.';
        this.loading = false;
      },
    });
  }

  private isRegisterPasswordStrong(): boolean {
    return this.password.length >= 8 && /[a-z]/.test(this.password) && /[A-Z]/.test(this.password) && /\d/.test(this.password);
  }

  private isEmailLike(): boolean {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(this.email.trim());
  }

  private hasGoogleClientId(): boolean {
    if (typeof window === 'undefined') {
      return false;
    }

    let localClientId = '';
    try {
      localClientId = window.localStorage?.getItem('AIRRAL_GOOGLE_CLIENT_ID') || '';
    } catch {
      localClientId = '';
    }

    const runtimeClientId = (window as any).AIRRAL_RUNTIME_CONFIG?.googleClientId || '';
    const metaClientId = document
      .querySelector<HTMLMetaElement>('meta[name="airral-google-client-id"]')
      ?.content || '';

    return Boolean((runtimeClientId || metaClientId || localClientId).trim());
  }

  private handleAuthSuccess(response: AuthResponse): void {
    const role = response.role || USER_ROLES.APPLICANT;
    const normalizedRole = role.toUpperCase();
    if (normalizedRole !== USER_ROLES.APPLICANT && normalizedRole !== USER_ROLES.ADMIN) {
      this.errorMessage = 'This login is for applicants. Employer users should use the HR portal.';
      this.loading = false;
      this.googleLoading = false;
      return;
    }

    const user: User = {
      id: response.userId ?? 0,
      email: response.email || response.userEmail || this.email.trim(),
      firstName: response.firstName,
      lastName: response.lastName,
      roles: [role],
      role,
      isActive: true,
    };

    this.authService.login(user, response.token);
    this.loading = false;
    this.googleLoading = false;
    this.router.navigateByUrl(this.resolvePostAuthUrl());
  }

  private resolvePostAuthUrl(): string {
    if (this.isRegisterMode) {
      return '/onboarding';
    }

    return safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl'));
  }
}
