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
  /** Inline SVG path data, drawn on a 24x24 grid. */
  icon: string;
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
      // eye
      icon: 'M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z M12 9.4a2.6 2.6 0 1 0 0 5.2 2.6 2.6 0 0 0 0-5.2z',
      title: 'It looks, you don’t',
      description:
        'Tell it what you’re after once. It keeps watching, day and night, and only interrupts when something actually fits.',
    },
    {
      // speech mark
      icon: 'M20 4H4a1.6 1.6 0 0 0-1.6 1.6v9.2A1.6 1.6 0 0 0 4 16.4h3.4L12 21l4.6-4.6H20a1.6 1.6 0 0 0 1.6-1.6V5.6A1.6 1.6 0 0 0 20 4z M8 10.2h8',
      title: 'It explains itself',
      description:
        'Every suggestion comes with the reason behind it. No black box, no mystery score — just a straight answer you can argue with.',
    },
    {
      // document with a check
      icon: 'M15 3H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2V7z M15 3v4h4 M9.5 14.5l1.8 1.8 3.7-4',
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
  }

  ngOnDestroy(): void {
    this.observers.forEach((o) => o.disconnect());
    this.timers.forEach((t) => clearTimeout(t));
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
