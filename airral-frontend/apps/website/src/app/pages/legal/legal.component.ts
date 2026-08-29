// apps/website/src/app/pages/legal/legal.component.ts
import { AfterViewInit, Component, Inject, OnDestroy, PLATFORM_ID } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-legal',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './legal.component.html',
  styleUrls: ['./legal.component.css'],
})
export class LegalComponent implements AfterViewInit, OnDestroy {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  private observers: IntersectionObserver[] = [];

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
