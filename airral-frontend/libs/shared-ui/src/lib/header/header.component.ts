import { CommonModule } from '@angular/common';
import { Component, HostListener, Input, signal } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

export interface HeaderNavLink {
  label: string;
  path: string;
  /** Use exact match for active state (e.g. home route). */
  exact?: boolean;
  /** Render as external anchor instead of RouterLink. */
  external?: boolean;
}

export interface HeaderCta {
  label: string;
  path: string;
  variant?: 'primary' | 'ghost';
  external?: boolean;
}

@Component({
  selector: 'airral-header',
  standalone: true,
  imports: [CommonModule, RouterLink, RouterLinkActive],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.css'],
})
export class HeaderComponent {
  @Input() brand = 'AIRRAL';
  @Input() tagline = 'Talent · Hiring · Growth';
  @Input() links: HeaderNavLink[] = [
    { label: 'Home', path: '/', exact: true },
    { label: 'Jobs', path: '/jobs' },
    { label: 'For Employers', path: '/for-employers' },
    { label: 'About', path: '/about' },
  ];
  @Input() ctas: HeaderCta[] = [
    { label: 'Sign in', path: '/login', variant: 'ghost' },
    { label: 'Get started', path: '/apply', variant: 'primary' },
  ];

  readonly menuOpen = signal(false);
  readonly scrolled = signal(false);

  toggleMenu(): void {
    this.menuOpen.update((v) => !v);
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  @HostListener('window:scroll')
  onScroll(): void {
    this.scrolled.set(window.scrollY > 8);
  }
}
