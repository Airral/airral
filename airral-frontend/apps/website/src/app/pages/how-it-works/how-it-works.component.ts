import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';

@Component({
  selector: 'app-how-it-works',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './how-it-works.component.html',
  styleUrls: ['./how-it-works.component.css'],
})
export class HowItWorksComponent {
  readonly candidateSteps = [
    {
      number: 1,
      title: 'Create Your Profile',
      description: 'Build a comprehensive profile showcasing your skills, experience, and career goals.',
    },
    {
      number: 2,
      title: 'Get Matched',
      description: 'Our AI finds job opportunities perfectly aligned with your expertise and preferences.',
    },
    {
      number: 3,
      title: 'Apply with One Click',
      description: 'Submit tailored applications instantly. Your profile is automatically pre-screened.',
    },
    {
      number: 4,
      title: 'Track & Interview',
      description: 'Monitor your applications in real-time and schedule interviews seamlessly.',
    },
    {
      number: 5,
      title: 'Get Hired',
      description: 'Receive and accept offers. Start your new role with confidence.',
    },
  ];

  readonly employerSteps = [
    {
      number: 1,
      title: 'Post a Job',
      description: 'Create a job listing with detailed requirements. Our system learns what you need.',
    },
    {
      number: 2,
      title: 'Get Qualified Candidates',
      description: 'AI screening surfaces top-fit candidates ranked by compatibility.',
    },
    {
      number: 3,
      title: 'Evaluate & Collaborate',
      description: 'Built-in tools for screening, interviewing, and team feedback.',
    },
    {
      number: 4,
      title: 'Make an Offer',
      description: 'Send offer letters and track acceptance with integrated workflows.',
    },
    {
      number: 5,
      title: 'Onboard New Team Member',
      description: 'Automated onboarding checklists and document management.',
    },
  ];

  readonly headerLinks: HeaderNavLink[] = [
    { label: 'Home', path: '/' },
    { label: 'For Employers', path: '/for-employers' },
    { label: 'Pricing', path: '/pricing' },
  ];

  readonly headerCtas: HeaderCta[] = [
    { label: 'Get Started', path: '/apply' },
  ];

  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'How It Works',
    links: [
      { label: 'Home', path: '/' },
      { label: 'For Employers', path: '/for-employers' },
      { label: 'Pricing', path: '/pricing' },
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
