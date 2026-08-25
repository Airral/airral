import { CommonModule, isPlatformBrowser } from '@angular/common';
import { Component, Inject, OnInit, PLATFORM_ID } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, HeaderComponent, FooterComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent implements OnInit {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  private readonly isBrowser: boolean;

  constructor(
    @Inject(PLATFORM_ID) platformId: object,
    private readonly route: ActivatedRoute
  ) {
    this.isBrowser = isPlatformBrowser(platformId);
  }

  get applicantLoginUrl(): string {
    return this.buildApplicantAuthUrl();
  }

  get applicantRegisterUrl(): string {
    return this.buildApplicantAuthUrl('register');
  }

  ngOnInit(): void {
    if (this.isBrowser) {
      window.location.replace(this.applicantLoginUrl);
    }
  }

  private buildApplicantAuthUrl(mode?: 'register'): string {
    const url = new URL(`${PORTAL_ROUTES.APPLICANT}/login`);
    if (mode === 'register') {
      url.searchParams.set('mode', 'register');
    }

    const queryParams = this.route.snapshot.queryParamMap;
    queryParams.keys.forEach((key) => {
      const value = queryParams.get(key);
      if (value && key !== 'mode') {
        url.searchParams.set(key, value);
      }
    });

    return url.toString();
  }
}
