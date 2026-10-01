// apps/website/src/app/pages/login/login.component.ts
import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { AudienceChoiceComponent } from '../../shared/audience-choice.component';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

/**
 * Sign in. It used to forward everyone to the job seeker portal, so a company
 * signing in from the website landed in the wrong place. It now asks.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, HeaderComponent, FooterComponent, AudienceChoiceComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  /** Anything a link carried (such as returnUrl), passed on to the job seeker sign-in. */
  readonly seekerParams: Record<string, string>;

  constructor(route: ActivatedRoute) {
    const query = route.snapshot.queryParamMap;
    this.seekerParams = Object.fromEntries(
      query.keys.filter((key) => key !== 'mode').map((key) => [key, query.get(key) ?? ''])
    );
  }
}
