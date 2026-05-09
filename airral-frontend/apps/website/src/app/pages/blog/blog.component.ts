import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-blog',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './blog.component.html',
  styleUrls: ['./blog.component.css'],
})
export class BlogComponent {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Blog',
    links: [{ label: 'Home', path: '/' }],
    ctas: [{ label: 'Subscribe', path: '#', external: false }],
  };
  readonly footerConfig = {
    brand: 'AIRRAL',
    tagline: 'Fair hiring for everyone.',
    columns: [
      { title: 'Blog', links: [{ label: 'Latest Posts', path: '/blog' }] },
      { title: 'Company', links: [{ label: 'About', path: '/about' }] },
      { title: 'Legal', links: [{ label: 'Privacy', path: '/privacy' }] },
    ],
  };
}
