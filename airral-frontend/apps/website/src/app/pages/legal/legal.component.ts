import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-legal',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './legal.component.html',
  styleUrls: ['./legal.component.css'],
})
export class LegalComponent {
  readonly legalType = 'terms'; // Can be 'terms', 'privacy', or 'cookies'
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Legal',
    links: [{ label: 'Home', path: '/' }],
    ctas: [{ label: 'Contact', path: '/contact', external: false }],
  };
  readonly footerConfig = {
    brand: 'AIRRAL',
    tagline: 'Fair hiring for everyone.',
    columns: [
      { title: 'Legal', links: [{ label: 'Terms', path: '/terms' }, { label: 'Privacy', path: '/privacy' }] },
      { title: 'Company', links: [{ label: 'About', path: '/about' }] },
      { title: 'Contact', links: [{ label: 'Support', path: '/contact' }] },
    ],
  };
}
