import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-job-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './job-detail.component.html',
  styleUrls: ['./job-detail.component.css'],
})
export class JobDetailComponent {
  job = {
    id: '1',
    title: 'Senior Full-Stack Developer',
    company: 'TechCorp',
    location: 'San Francisco, CA',
    type: 'Full-time',
    salary: '$150k - $200k',
    department: 'Engineering',
    description: 'We are looking for an experienced full-stack developer to join our growing team...',
    requirements: ['5+ years of experience', 'React/Node.js expertise', 'AWS knowledge', 'Team collaboration'],
    benefits: ['Health insurance', '401k match', 'Remote flexibility', 'Learning budget'],
  };
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Job Details',
    links: [
      { label: 'Home', path: '/' },
      { label: 'Jobs', path: '/jobs' },
    ],
    ctas: [
      { label: 'Apply Now', path: '#', external: false },
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

  constructor(private route: ActivatedRoute) {}
}
