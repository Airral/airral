// apps/website/src/app/pages/contact/contact.component.ts
import {
  AfterViewInit,
  Component,
  Inject,
  OnDestroy,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

/** Which inline icon a channel draws. */
type ChannelIcon = 'mail' | 'sales' | 'help';

interface Channel {
  icon: ChannelIcon;
  title: string;
  detail: string;
  /** External destination — mailto, or an absolute URL. */
  href?: string;
  /** Internal destination, routed. */
  route?: string;
}

interface Faq {
  question: string;
  answer: string;
}

@Component({
  selector: 'app-contact',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, HeaderComponent, FooterComponent],
  templateUrl: './contact.component.html',
  styleUrls: ['./contact.component.css'],
})
export class ContactComponent implements AfterViewInit, OnDestroy {
  formData = {
    name: '',
    email: '',
    subject: '',
    message: '',
  };

  submitted = false;

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly channels: Channel[] = [
    {
      icon: 'mail',
      title: 'Support',
      detail: 'hello@airral.com',
      href: 'mailto:hello@airral.com',
    },
    {
      icon: 'sales',
      title: 'Hiring with us',
      detail: 'sales@airral.com',
      href: 'mailto:sales@airral.com',
    },
    {
      icon: 'help',
      title: 'Help centre',
      detail: 'Answers to the common questions',
      route: '/help',
    },
  ];

  readonly faqs: Faq[] = [
    {
      question: 'When will I hear back?',
      answer:
        'We read everything that arrives and reply within one business day. Professional and Enterprise plans are answered first.',
    },
    {
      question: 'Can we talk on the phone?',
      answer:
        'Phone support comes with the Professional and Enterprise plans. Email hello@airral.com and we will book a time.',
    },
    {
      question: 'Can I see it before I buy it?',
      answer:
        'Yes. Email sales@airral.com and we will walk you through the parts of Airral your team would actually use.',
    },
  ];

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

  onSubmit() {
    if (this.formData.name && this.formData.email && this.formData.message) {
      // In a real app, you'd send this to a backend API
      console.log('Form submitted:', this.formData);
      this.submitted = true;
      this.formData = { name: '', email: '', subject: '', message: '' };
      setTimeout(() => (this.submitted = false), 3000);
    }
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
