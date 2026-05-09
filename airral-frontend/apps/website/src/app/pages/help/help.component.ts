import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-help',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './help.component.html',
  styleUrls: ['./help.component.css'],
})
export class HelpComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Help & Resources',
    links: [{ label: 'Home', path: '/' }],
    ctas: [{ label: 'Contact Support', path: '/contact', external: false }],
  };
  readonly footerConfig = {
    brand: 'AIRRAL',
    tagline: 'Fair hiring for everyone.',
    columns: [
      { title: 'Help', links: [{ label: 'Contact', path: '/contact' }] },
      { title: 'Company', links: [{ label: 'About', path: '/about' }] },
      { title: 'Legal', links: [{ label: 'Privacy', path: '/privacy' }] },
    ],
  };
}
