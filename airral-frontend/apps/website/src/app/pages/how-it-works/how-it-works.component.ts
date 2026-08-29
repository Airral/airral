// apps/website/src/app/pages/how-it-works/how-it-works.component.ts
import {
  AfterViewInit,
  Component,
  Inject,
  OnDestroy,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { PORTAL_ROUTES } from '@airral/shared-utils';

interface Step {
  number: number;
  title: string;
  description: string;
}

@Component({
  selector: 'app-how-it-works',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './how-it-works.component.html',
  styleUrls: ['./how-it-works.component.css'],
})
export class HowItWorksComponent implements AfterViewInit, OnDestroy {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  private observers: IntersectionObserver[] = [];

  readonly candidateSteps: Step[] = [
    {
      number: 1,
      title: 'Say what you’re after',
      description:
        'Once: what you’ve done, what you want next, and where you’re willing to work. That is the whole setup.',
    },
    {
      number: 2,
      title: 'Airral does the looking',
      description:
        'It keeps watching what’s open and puts a role in front of you when it fits — with the reason it thinks so.',
    },
    {
      number: 3,
      title: 'Your résumé gets reworked',
      description:
        'Before you apply, it rewrites your résumé for that specific role: the wording they used, the things they asked for twice.',
    },
    {
      number: 4,
      title: 'Apply and follow it',
      description:
        'Send the application and see where it actually is — screening, interview, decision — instead of guessing.',
    },
    {
      number: 5,
      title: 'Interviews and offers',
      description:
        'Times get agreed and offers arrive in the same place you applied from. Nothing lives in your inbox.',
    },
  ];

  readonly employerSteps: Step[] = [
    {
      number: 1,
      title: 'Post the role',
      description:
        'Write it once, with what the job actually needs. That description is what candidates are measured against.',
    },
    {
      number: 2,
      title: 'Applications arrive screened',
      description:
        'Each one is checked against the role and ranked, with the reasoning attached so you can disagree with it.',
    },
    {
      number: 3,
      title: 'Interview as a team',
      description:
        'Scheduling and feedback sit on the role, so nobody has to chase who spoke to whom.',
    },
    {
      number: 4,
      title: 'Make the offer',
      description: 'Draft it, get it approved, send it, and see whether it was accepted.',
    },
    {
      number: 5,
      title: 'Start them properly',
      description: 'Onboarding checklists and the documents a new hire has to sign, in one place.',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      return;
    }

    this.watchReveals();
  }

  ngOnDestroy(): void {
    this.observers.forEach((o) => o.disconnect());
  }

  /** Reveal each `.rise` element once, as it comes into view. */
  private watchReveals(): void {
    const els = Array.from(document.querySelectorAll<HTMLElement>('.rise'));
    if (!els.length) {
      return;
    }

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-in');
            io.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.16 }
    );

    els.forEach((el, i) => {
      el.style.transitionDelay = `${Math.min(i % 5, 4) * 80}ms`;
      io.observe(el);
    });
    this.observers.push(io);
  }
}
