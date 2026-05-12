import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-pricing',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './pricing.component.html',
  styleUrls: ['./pricing.component.css'],
})
export class PricingComponent {
  // Pricing for companies using AIRRAL ATS
  // Note: Job seekers ALWAYS use AIRRAL for free
  readonly plans = [
    {
      name: 'Quick Hire',
      price: 'Free',
      period: '',
      description: 'Perfect for startups and small businesses',
      features: [
        'Up to 5 active jobs',
        '3 team members',
        'Basic applicant tracking',
        'Email support',
        'Application forms',
        'Candidate profiles',
      ],
      cta: 'Get Started',
      highlighted: false,
      popular: false,
    },
    {
      name: 'Professional',
      price: '$199',
      period: '/month',
      description: 'For growing companies',
      features: [
        'Unlimited job postings',
        'Up to 20 team members',
        'Advanced analytics',
        'Interview scheduling',
        'Calendar integration',
        'Department management',
        'Priority support',
        'Custom workflows',
      ],
      cta: 'Start Free Trial',
      highlighted: true,
      popular: true,
    },
    {
      name: 'Enterprise',
      price: '$499',
      period: '/month',
      description: 'For large organizations',
      features: [
        'Everything in Professional',
        'Unlimited team members',
        'White label option',
        'API access',
        'Custom integrations',
        'SSO authentication',
        'Dedicated support',
        '99.9% SLA',
      ],
      cta: 'Contact Sales',
      highlighted: false,
      popular: false,
    },
  ];

  readonly faqs = [
    {
      question: 'Is AIRRAL free for job seekers?',
      answer: 'Yes! Job seekers can browse jobs, apply, and track applications completely free. AIRRAL is always free for candidates.',
    },
    {
      question: 'Is there a free trial for companies?',
      answer: 'Yes! Quick Hire is free forever with up to 5 active jobs. Professional and Enterprise plans offer a 14-day free trial with full access.',
    },
    {
      question: 'What payment methods do you accept?',
      answer: 'We accept all major credit cards (Visa, Mastercard, Amex), ACH bank transfers, and wire transfers for enterprise customers.',
    },
    {
      question: 'Can I upgrade or downgrade my plan?',
      answer: 'Yes, you can change your plan at any time. Upgrades take effect immediately. Downgrades take effect at the end of your billing cycle.',
    },
  ];

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Pricing',
    links: [
      { label: 'Home', path: '/' },
      { label: 'How It Works', path: '/how-it-works' },
      { label: 'Contact', path: '/contact' },
    ],
    ctas: [
      { label: 'Get Started', path: '/apply', external: false },
    ],
  };

  readonly footerConfig = {
    brand: 'AIRRAL',
    tagline: 'Fair hiring for everyone.',
    columns: [
      {
        title: 'Product',
        links: [
          { label: 'For Candidates', path: '/' },
          { label: 'For Employers', path: '/for-employers' },
          { label: 'Pricing', path: '/pricing' },
        ],
      },
      {
        title: 'Company',
        links: [
          { label: 'About Us', path: '/about' },
          { label: 'Contact', path: '/contact' },
          { label: 'Blog', path: '/blog' },
        ],
      },
      {
        title: 'Resources',
        links: [
          { label: 'Help Center', path: '/help' },
          { label: 'Privacy', path: '/privacy' },
          { label: 'Terms', path: '/terms' },
        ],
      },
    ],
  };
}
