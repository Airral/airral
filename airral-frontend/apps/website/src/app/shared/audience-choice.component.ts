import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PORTAL_ROUTES } from '@airral/shared-utils';

/**
 * Who is who: job seekers and companies use different AIRRAL portals with
 * different accounts. Shown wherever someone has to pick one -- the sign-in
 * page and the home page -- so nobody signs up for the wrong thing.
 *
 * intent 'sign-in' leads with signing in; 'start' leads with getting started.
 */
@Component({
  selector: 'app-audience-choice',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="grid grid--2 choice">
      <article class="card glass choice__card" aria-labelledby="choice-seeker">
        <span class="glyph" aria-hidden="true">
          <svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
          </svg>
        </span>
        <p class="kicker choice__kicker">For job seekers</p>
        <h2 class="h3 choice__h" id="choice-seeker">I'm looking for a job</h2>
        <p class="choice__p">
          Search jobs, see how well your résumé fits each one, and track your applications.
          Free, in the AIRRAL job seeker portal.
        </p>
        <div class="cta-row choice__cta">
          <ng-container *ngIf="intent === 'sign-in'; else seekerStart">
            <a class="btn" [href]="seekerSignIn">Job seeker sign in</a>
            <a class="btn btn--glass" [href]="seekerRegister">Create a free account</a>
          </ng-container>
          <ng-template #seekerStart>
            <a class="btn" [href]="seekerRegister">Find a job</a>
            <a class="btn btn--glass" [href]="seekerSignIn">Sign in</a>
          </ng-template>
        </div>
        <p class="fine choice__fine">Free for job seekers. No card, and you can look around without an account.</p>
      </article>

      <article class="card glass choice__card" aria-labelledby="choice-company">
        <span class="glyph" aria-hidden="true">
          <svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="7" width="18" height="13" rx="2" /><path d="M9 7V5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2" />
          </svg>
        </span>
        <p class="kicker choice__kicker">For companies</p>
        <h2 class="h3 choice__h" id="choice-company">I hire for a company</h2>
        <p class="choice__p">
          Post jobs, review applicants, schedule interviews and send offers in your company's
          own workspace, the AIRRAL company portal.
        </p>
        <div class="cta-row choice__cta">
          <ng-container *ngIf="intent === 'sign-in'; else companyStart">
            <a class="btn" [href]="companySignIn">Company sign in</a>
            <a class="btn btn--glass" routerLink="/sign-up">Create a company account</a>
          </ng-container>
          <ng-template #companyStart>
            <a class="btn" routerLink="/sign-up">Post a job</a>
            <a class="btn btn--glass" [href]="companySignIn">Company sign in</a>
          </ng-template>
        </div>
        <p class="fine choice__fine">
          Invited by your team? Open the link in your invitation email to join their workspace.
        </p>
      </article>
    </div>
  `,
  styles: [
    `
      .choice { align-items: stretch; }
      .choice__card { display: flex; flex-direction: column; align-items: flex-start; text-align: left; }
      .choice__card:hover { transform: none; }
      .choice__kicker { margin-top: 22px; }
      .choice__h { margin: 8px 0 0; }
      .choice__p { margin: 12px 0 0; color: var(--ink-2); line-height: 1.55; }
      .choice__cta { margin-top: auto; padding-top: 26px; justify-content: flex-start; flex-wrap: wrap; }
      .choice__fine { margin-top: 16px; text-align: left; }
    `,
  ],
})
export class AudienceChoiceComponent {
  @Input() intent: 'sign-in' | 'start' = 'start';
  /** Extra query parameters to carry into the job seeker sign-in (e.g. returnUrl). */
  @Input() seekerParams: Record<string, string> = {};

  readonly companySignIn = `${PORTAL_ROUTES.HR}/login`;

  get seekerSignIn(): string {
    return this.seekerUrl();
  }

  get seekerRegister(): string {
    return this.seekerUrl({ mode: 'register' });
  }

  private seekerUrl(extra: Record<string, string> = {}): string {
    const url = new URL(`${PORTAL_ROUTES.APPLICANT}/login`);
    Object.entries({ ...this.seekerParams, ...extra }).forEach(([key, value]) => url.searchParams.set(key, value));
    return url.toString();
  }
}
