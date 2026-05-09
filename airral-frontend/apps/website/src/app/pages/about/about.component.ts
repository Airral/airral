import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

interface TeamMember {
  name: string;
  role: string;
  bio: string;
  image?: string;
}

interface Milestone {
  year: number;
  event: string;
}

@Component({
  selector: 'app-about',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './about.component.html',
  styleUrls: ['./about.component.css'],
})
export class AboutComponent {
  readonly mission =
    'To revolutionize hiring by making it faster, fairer, and more human-centered.';

  readonly values = [
    {
      title: 'Inclusivity',
      description: 'We believe in removing barriers and creating equal opportunities for all.',
    },
    {
      title: 'Transparency',
      description: 'Clear communication builds trust. We keep candidates and teams informed every step.',
    },
    {
      title: 'Innovation',
      description: 'We leverage AI and modern tools to make hiring smarter and less biased.',
    },
    {
      title: 'Speed',
      description: 'Fast hiring doesn\'t mean rushed. We find the right match quickly.',
    },
  ];

  // Timeline updated to reflect current product status
  readonly milestones: Milestone[] = [
    { year: 2026, event: 'AIRRAL ATS platform launched with multi-tenant architecture' },
    { year: 2026, event: 'Full-featured HR portal with analytics and interview scheduling' },
    { year: 2026, event: 'Quick Hire free tier introduced for startups' },
    { year: 2026, event: 'Enterprise features: SSO, API access, white label' },
  ];

  // Team section
  readonly team: TeamMember[] = [
    {
      name: 'Dagimawi Tamrat',
      role: 'CEO',
      bio: 'Building the best ATS for fast-growing companies and shaping the future of fair, efficient hiring.',
      image: '/assets/team/dagimawi.png',
    }
  ];

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

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
          { label: 'How It Works', path: '/how-it-works' },
        ],
      },
      {
        title: 'Company',
        links: [
          { label: 'About Us', path: '/about' },
          { label: 'Blog', path: '/blog' },
          { label: 'Careers', path: '/careers' },
          { label: 'Contact', path: '/contact' },
        ],
      },
      {
        title: 'Resources',
        links: [
          { label: 'Help Center', path: '/help' },
          { label: 'Privacy', path: '/privacy' },
          { label: 'Terms', path: '/terms' },
          { label: 'Status', path: 'https://status.airral.io', external: true },
        ],
      },
    ],
  };
}
