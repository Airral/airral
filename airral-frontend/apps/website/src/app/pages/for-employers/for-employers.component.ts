import {
  Component,
  Inject,
  PLATFORM_ID,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface EmployerBenefit {
  /** Path data for the card's inline icon. */
  path: string;
  title: string;
  description: string;
}

interface EmployerStep {
  n: string;
  title: string;
  description: string;
}

interface EmployerPlan {
  name: string;
  price: string;
  period: string;
  description: string;
  features: string[];
  cta: string;
  route: string;
  highlighted: boolean;
}

@Component({
  selector: 'app-for-employers',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './for-employers.component.html',
  styleUrls: ['./for-employers.component.css'],
})
export class ForEmployersComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly benefits: EmployerBenefit[] = [
    {
      path: 'M4 6h16M4 12h16M4 18h10',
      title: 'One place for every role',
      description:
        'Post a role, publish the application, and every candidate who applies stays attached to the job they applied to.',
    },
    {
      path: 'M4 19V5m0 14h16M8 15V9m4 6V7m4 8v-4',
      title: 'Stages you can see',
      description:
        'Open roles, candidate stages and what is waiting on a decision, without a spreadsheet anyone has to remember to update.',
    },
    {
      path: 'M16 20v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 18.5V20M10 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7M17 4.5a3.5 3.5 0 0 1 0 6.9M20 20v-1.5a3.5 3.5 0 0 0-2.5-3.35',
      title: 'Decisions with the team',
      description:
        'Invite owners, hiring managers and interviewers. Their notes and feedback sit with the candidate, not in a thread.',
    },
    {
      path: 'M6 3h9l5 5v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm8 0v6h6M9 14l2 2 4-4',
      title: 'Offers and follow-up',
      description:
        'Draft, approve and send an offer from the same record, and keep applicants told where they stand.',
    },
  ];

  readonly steps: EmployerStep[] = [
    {
      n: '1',
      title: 'Open the role',
      description:
        'Create a role, publish the application, and keep every inbound candidate attached to the right job.',
    },
    {
      n: '2',
      title: 'Review together',
      description:
        'Move candidates through stages with notes, scorecards and team feedback in one shared view.',
    },
    {
      n: '3',
      title: 'Interview and close',
      description:
        'Schedule the next step, decide with context, and keep candidates informed through the process.',
    },
  ];

  readonly plans: EmployerPlan[] = [
    {
      name: 'Quick Hire',
      price: 'Free',
      period: '',
      description: 'For a first few hires.',
      features: [
        'Up to 5 active jobs',
        '3 team members',
        'Basic applicant tracking',
        'Email support',
        'Application forms',
      ],
      cta: 'Get started',
      route: '/sign-up',
      highlighted: false,
    },
    {
      name: 'Professional',
      price: '$199',
      period: '/month',
      description: 'For a team hiring continuously.',
      features: [
        'Unlimited job postings',
        'Up to 20 team members',
        'Advanced analytics',
        'Interview scheduling',
        'Calendar integration',
        'Priority support',
      ],
      cta: 'Start free trial',
      route: '/sign-up',
      highlighted: true,
    },
    {
      name: 'Enterprise',
      price: '$499',
      period: '/month',
      description: 'For larger organisations.',
      features: [
        'Everything in Professional',
        'Unlimited team members',
        'White label option',
        'API access',
        'SSO authentication',
        'Dedicated support',
      ],
      cta: 'Contact sales',
      route: '/contact',
      highlighted: false,
    },
  ];

  constructor(@Inject(PLATFORM_ID) private readonly platformId: object) {}

}
