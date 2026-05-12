import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface EmployerBenefit {
  icon: string;
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
  readonly benefits: EmployerBenefit[] = [
    {
      icon: '🎯',
      title: 'Owner-ready ATS',
      description: 'Post roles, collect applications, review candidates, and keep every decision in one workspace.',
    },
    {
      icon: '⚡',
      title: 'Move with focus',
      description: 'Track who needs review, what interview comes next, and which candidates are ready for a decision.',
    },
    {
      icon: '💰',
      title: 'Built for lean teams',
      description: 'Start free with Quick Hire, then upgrade only when your hiring motion needs more room.',
    },
    {
      icon: '📊',
      title: 'Pipeline clarity',
      description: 'See active roles, candidate stages, team feedback, and offer work without spreadsheet drift.',
    },
    {
      icon: '🤝',
      title: 'Team decisions',
      description: 'Invite owners, hiring managers, and interviewers to evaluate candidates with shared context.',
    },
    {
      icon: '🔒',
      title: 'Candidate respect',
      description: 'Give applicants a cleaner process with organized roles, status visibility, and timely follow-up.',
    },
  ];

  readonly plans: EmployerPlan[] = [
    {
      name: 'Quick Hire',
      price: 'Free',
      period: '',
      description: 'Perfect for startups and small businesses',
      features: ['Up to 5 active jobs', '3 team members', 'Basic applicant tracking', 'Email support', 'Application forms'],
      cta: 'Get Started',
      route: '/sign-up',
      highlighted: false,
    },
    {
      name: 'Professional',
      price: '$199',
      period: '/month',
      description: 'For growing companies',
      features: ['Unlimited job postings', 'Up to 20 team members', 'Advanced analytics', 'Interview scheduling', 'Calendar integration', 'Priority support'],
      cta: 'Start Free Trial',
      route: '/sign-up',
      highlighted: true,
    },
    {
      name: 'Enterprise',
      price: '$499',
      period: '/month',
      description: 'For large organizations',
      features: ['Everything in Professional', 'Unlimited team members', 'White label option', 'API access', 'SSO authentication', 'Dedicated support'],
      cta: 'Contact Sales',
      route: '/contact',
      highlighted: false,
    },
  ];

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
}
