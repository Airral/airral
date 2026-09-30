import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AiConnectComponent } from '@airral/shared-ui';

/** Connect an AI assistant to AIRRAL: the applicant's page around the shared component. */
@Component({
  selector: 'app-ai-connect-page',
  standalone: true,
  imports: [RouterLink, AiConnectComponent],
  template: `
    <section class="page">
      <a class="back" routerLink="/profile"><span class="material-icons" aria-hidden="true">arrow_back</span>Profile</a>
      <header>
        <h1>Connect an AI assistant</h1>
        <p>Search AIRRAL's jobs from Claude or another AI app, with a key only you hold.</p>
      </header>
      <airral-ai-connect audience="applicant" />
    </section>
  `,
  styles: [
    `
      :host { display: block; }
      .page { max-width: 860px; margin: 0 auto; padding: 24px 16px 48px; display: grid; gap: 16px; }
      .back { display: inline-flex; align-items: center; gap: 4px; width: fit-content; color: var(--ap-ink-2); font-size: 13px; font-weight: 700; text-decoration: none; }
      .back .material-icons { font-size: 18px; }
      .back:hover { color: var(--ap-ink); }
      h1 { margin: 0; font: 800 30px/1.15 'Sora', 'Manrope', sans-serif; letter-spacing: -0.02em; color: var(--ap-ink); }
      header p { margin: 6px 0 0; color: var(--ap-ink-2); font-size: 14px; }
      @media (max-width: 600px) { h1 { font-size: 26px; } }
    `,
  ],
})
export class AiConnectPageComponent {}
