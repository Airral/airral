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

/**
 * Sign in goes to /login, which asks whether you are looking for a job or
 * hiring, because the two sign in to different portals. The main button is
 * for job seekers, who are most of the visitors.
 */
export const WEBSITE_HEADER_CTAS: HeaderCta[] = [
  { label: 'Sign in', path: '/login', variant: 'ghost' },
  { label: 'Find a job', path: `${PORTAL_ROUTES.APPLICANT}/login?mode=register`, external: true },
];

/** On pages for companies: their own sign-in, and their first step. */
export const EMPLOYER_HEADER_CTAS: HeaderCta[] = [
  { label: 'Company sign in', path: `${PORTAL_ROUTES.HR}/login`, variant: 'ghost', external: true },
  { label: 'Post a job', path: '/sign-up' },
];
