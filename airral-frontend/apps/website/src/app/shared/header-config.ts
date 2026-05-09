import { HeaderNavLink, HeaderCta } from '@airral/shared-ui';

/**
 * Standard website header navigation configuration
 * Use this across all pages to maintain consistency
 */
export const WEBSITE_HEADER_LINKS: HeaderNavLink[] = [
  { label: 'Home', path: '/' },
  { label: 'Jobs', path: '/jobs' },
  { label: 'For Employers', path: '/for-employers' },
  { label: 'Pricing', path: '/pricing' },
  { label: 'About', path: '/about' },
];

export const WEBSITE_HEADER_CTAS: HeaderCta[] = [
  { label: 'Sign In', path: '/login', variant: 'ghost' },
  { label: 'Get Started', path: '/sign-up' },
];
