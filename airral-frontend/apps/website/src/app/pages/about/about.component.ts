// apps/website/src/app/pages/about/about.component.ts
import {
  AfterViewInit,
  Component,
  Inject,
  OnDestroy,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface Principle {
  title: string;
  description: string;
}

@Component({
  selector: 'app-about',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './about.component.html',
  styleUrls: ['./about.component.css'],
})
export class AboutComponent implements AfterViewInit, OnDestroy {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  private observers: IntersectionObserver[] = [];

  /** How we decide what to build. Each one describes something the product does. */
  readonly principles: Principle[] = [
    {
      title: 'Say why',
      description:
        'Every match comes with the reason behind it, in plain words. No score you cannot question, no ranking you cannot see the workings of.',
    },
    {
      title: 'Do the reading',
      description:
        'Airral watches for openings so you do not have to sit on job boards. It speaks up when something fits, and stays quiet when nothing does.',
    },
    {
      title: 'One place per hire',
      description:
        'Applications, interviews, feedback and offers live together. Nobody has to ask where a candidate got to or who said what.',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
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
