import { HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { PORTAL_ROUTES } from '@airral/shared-utils';

/**
 * Standard website header navigation configuration
 * Use this across all pages to maintain consistency
 */
export const WEBSITE_HEADER_LINKS: HeaderNavLink[] = [
  { label: 'Home', path: '/', exact: true },
  { label: 'Jobs', path: '/jobs' },
  { label: 'How it works', path: '/how-it-works' },
  { label: 'About', path: '/about' },
  { label: 'For employers', path: '/for-employers' },
];

export const WEBSITE_HEADER_CTAS: HeaderCta[] = [
  { label: 'Sign in', path: `${PORTAL_ROUTES.APPLICANT}/login`, variant: 'ghost', external: true },
  { label: 'Start job search', path: `${PORTAL_ROUTES.APPLICANT}/login?mode=register`, external: true },
];
