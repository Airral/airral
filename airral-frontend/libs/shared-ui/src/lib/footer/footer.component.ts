import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface FooterLink {
  label: string;
  path: string;
  external?: boolean;
}

export interface FooterColumn {
  title: string;
  links: FooterLink[];
}

@Component({
  selector: 'airral-footer',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './footer.component.html',
  styleUrls: ['./footer.component.css'],
})
export class FooterComponent {
  @Input() brand = 'AIRRAL';
  @Input() tagline =
    'The modern hiring platform — source, engage and hire great people, faster.';
  @Input() columns: FooterColumn[] = [
    {
      title: 'Product',
      links: [
        { label: 'Jobs', path: '/jobs' },
        { label: 'For Employers', path: '/for-employers' },
        { label: 'Pricing', path: '/pricing' },
      ],
    },
    {
      title: 'Company',
      links: [
        { label: 'About', path: '/about' },
        { label: 'Blog', path: '/blog' },
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
  ];

  readonly year = new Date().getFullYear();
}
