// apps/website/src/app/pages/pricing/pricing.component.ts
import {
  Component,
  Inject,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface Plan {
  name: string;
  price: string;
  period: string;
  description: string;
  features: string[];
  cta: string;
  /** External portal URL, or an in-app route. One of the two is set. */
  href?: string;
  route?: string;
}

interface Faq {
  question: string;
  answer: string;
}

@Component({
  selector: 'app-pricing',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './pricing.component.html',
  styleUrls: ['./pricing.component.css'],
})
export class PricingComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;
  readonly hrRegisterUrl = `${PORTAL_ROUTES.HR}/login?mode=register`;

  /**
   * Plans are for companies hiring on Airral. Candidates never pay —
   * see the free-for-candidates section on the page.
   */
  readonly plans: Plan[] = [
    {
      name: 'Quick Hire',
      price: 'Free',
      period: '',
      description: 'For a first few roles.',
      features: [
        'Up to 5 open jobs',
        '3 team members',
        'Applicant tracking',
        'Application forms',
        'Candidate profiles',
        'Email support',
      ],
      cta: 'Start free',
      href: this.hrRegisterUrl,
    },
    {
      name: 'Professional',
      price: '$199',
      period: '/month',
      description: 'For a team hiring regularly.',
      features: [
        'Unlimited job posts',
        'Up to 20 team members',
        'Interview scheduling',
        'Calendar integration',
        'Department management',
        'Custom workflows',
        'Reporting on your pipeline',
        'Priority support',
      ],
      cta: 'Start a trial',
      href: this.hrRegisterUrl,
    },
    {
      name: 'Enterprise',
      price: '$499',
      period: '/month',
      description: 'For hiring across many teams.',
      features: [
        'Everything in Professional',
        'Unlimited team members',
        'Single sign-on',
        'API access',
        'Custom integrations',
        'White label option',
        'A named contact for support',
      ],
      cta: 'Talk to us',
      route: '/contact',
    },
  ];

  readonly faqs: Faq[] = [
    {
      question: 'Is Airral free for job seekers?',
      answer:
        'Yes. Browsing jobs, applying and tracking your applications cost nothing, and there is no paid tier for candidates.',
    },
    {
      question: 'Can a company try it before paying?',
      answer:
        'Quick Hire is free for as long as you need it, with up to five open jobs. Professional and Enterprise come with a 14-day trial that has everything switched on.',
    },
    {
      question: 'What payment methods do you accept?',
      answer:
        'All major credit cards, ACH bank transfer, and wire transfer on Enterprise.',
    },
    {
      question: 'Can I change plan later?',
      answer:
        'Yes. An upgrade takes effect straight away. A downgrade takes effect at the end of the billing period you have already paid for.',
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

}
