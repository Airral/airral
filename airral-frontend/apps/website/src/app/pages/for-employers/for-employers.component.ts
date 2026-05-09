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
      title: 'Smart Matching',
      description: 'Our AI algorithm matches your job requirements with the best-fit candidates automatically.',
    },
    {
      icon: '⚡',
      title: '90% Faster Hiring',
      description: 'Reduce hiring time from months to weeks with streamlined workflows and qualified candidates.',
    },
    {
      icon: '💰',
      title: 'Reduce Costs',
      description: 'Save on recruitment agency fees and internal hiring costs. No hidden charges.',
    },
    {
      icon: '📊',
      title: 'Advanced Analytics',
      description: 'Track hiring metrics, candidate pipeline, and hiring performance in real-time.',
    },
    {
      icon: '🤝',
      title: 'Collaboration Tools',
      description: 'Built-in chat, interview scheduling, and feedback collection for your team.',
    },
    {
      icon: '🔒',
      title: 'Compliance Ready',
      description: 'Meet global hiring regulations and maintain audit trails for every hire.',
    },
  ];

  readonly plans = [
    {
      name: 'Quick Hire',
      price: 'Free',
      period: '',
      description: 'Perfect for startups',
      features: ['Up to 5 active jobs', '3 team members', 'Basic applicant tracking', 'Email support', 'Application forms'],
      cta: 'Get Started',
      highlighted: false,
    },
    {
      name: 'Professional',
      price: '$199',
      period: '/month',
      description: 'For growing companies',
      features: ['Unlimited job postings', 'Up to 20 team members', 'Advanced analytics', 'Interview scheduling', 'Calendar integration', 'Priority support'],
      cta: 'Start Free Trial',
      highlighted: true,
    },
    {
      name: 'Enterprise',
      price: '$499',
      period: '/month',
      description: 'For large organizations',
      features: ['Everything in Professional', 'Unlimited team members', 'White label option', 'API access', 'SSO authentication', 'Dedicated support'],
      cta: 'Contact Sales',
      highlighted: false,
    },
  ];

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
}
