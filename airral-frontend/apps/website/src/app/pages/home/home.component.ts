// apps/website/src/app/pages/home/home.component.ts
import {
  AfterViewInit,
  Component,
  ElementRef,
  Inject,
  OnDestroy,
  PLATFORM_ID,
  signal,
  viewChild,
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { PORTAL_ROUTES } from '@airral/shared-utils';

interface Promise_ {
  title: string;
  description: string;
}

interface Module {
  name: string;
  what: string;
}

/** What the assistant is asked, and what it answers. */
const ASK = 'find me a senior backend role, remote, that pays well';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent implements AfterViewInit, OnDestroy {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantPortal = PORTAL_ROUTES.APPLICANT;

  /** Typed into the terminal one character at a time. */
  readonly typed = signal('');
  readonly answered = signal(false);

  private readonly terminal = viewChild<ElementRef<HTMLElement>>('terminal');

  private observers: IntersectionObserver[] = [];
  private timers: ReturnType<typeof setTimeout>[] = [];

  readonly promises: Promise_[] = [
    {
      title: 'It looks, you don’t',
      description:
        'Tell it what you’re after once. It keeps watching, day and night, and only interrupts when something actually fits.',
    },
    {
      title: 'It explains itself',
      description:
        'Every suggestion comes with the reason behind it. No black box, no mystery score — just a straight answer you can argue with.',
    },
    {
      title: 'It gets you ready',
      description:
        'Before you apply, it reworks your résumé for that specific role — the wording they used, the things they asked for twice.',
    },
  ];

  readonly modules: Module[] = [
    { name: 'Pipeline', what: 'Everyone, every stage, one view.' },
    { name: 'Interviews', what: 'Scheduling and feedback on the role.' },
    { name: 'Offers', what: 'Draft, approve, send, track.' },
    { name: 'Referrals', what: 'Your team’s network, tracked properly.' },
    { name: 'Analytics', what: 'Where people drop off, and why.' },
    { name: 'Job posts', what: 'Publish once, reach people looking.' },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

  ngAfterViewInit(): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    if (reduced) {
      // Render the finished state rather than animating to it.
      this.typed.set(ASK);
      this.answered.set(true);
    } else {
      this.watchTerminal();
    }

    this.watchReveals();
  }

  ngOnDestroy(): void {
    this.observers.forEach((o) => o.disconnect());
    this.timers.forEach((t) => clearTimeout(t));
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

  /** The page's one orchestrated moment: the ask types, the answer lands. */
  private watchTerminal(): void {
    const host = this.terminal()?.nativeElement;
    if (!host) {
      return;
    }

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) {
            return;
          }
          io.unobserve(entry.target);
          this.timers.push(setTimeout(() => this.type(0), 280));
        });
      },
      { threshold: 0.4 }
    );

    io.observe(host);
    this.observers.push(io);
  }

  private type(i: number): void {
    if (i >= ASK.length) {
      this.timers.push(setTimeout(() => this.answered.set(true), 380));
      return;
    }
    this.typed.set(ASK.slice(0, i + 1));
    this.timers.push(setTimeout(() => this.type(i + 1), 34));
  }
}
