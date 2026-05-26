import { SeoConfig } from './seo.service';

export const DEFAULT_SEO: SeoConfig = {
  title: 'AIRRAL | Job Search Workspace for Better Roles',
  description:
    'Find fresh jobs, compare fit, check salary and company context, ask rooms, and apply with momentum in AIRRAL.',
  path: '/',
};

export const PAGE_SEO: Record<string, SeoConfig> = {
  home: {
    ...DEFAULT_SEO,
    structuredData: [
      {
        '@context': 'https://schema.org',
        '@type': 'FAQPage',
        mainEntity: [
          {
            '@type': 'Question',
            name: 'Is AIRRAL mainly for job seekers now?',
            acceptedAnswer: {
              '@type': 'Answer',
              text: 'Yes. AIRRAL opens with jobs first and helps candidates compare roles, save jobs, ask rooms, and apply with context.',
            },
          },
          {
            '@type': 'Question',
            name: 'What happens after I create a candidate account?',
            acceptedAnswer: {
              '@type': 'Answer',
              text: 'You land in the applicant portal, where jobs open first. From there you can improve matching, open job details, ask rooms, save roles, and attach resume checks to target jobs.',
            },
          },
          {
            '@type': 'Question',
            name: 'Is AIRRAL another job board?',
            acceptedAnswer: {
              '@type': 'Answer',
              text: 'No. AIRRAL is a job-search workspace where jobs are the entry point and fit, source context, rooms, events, and warm paths support each role.',
            },
          },
        ],
      },
    ],
  },
  jobs: {
    title: 'AIRRAL | Browse Jobs and Apply With Context',
    description:
      'Search open roles, compare job fit, salary, work mode, and source context, then apply with AIRRAL.',
    path: '/jobs',
  },
  apply: {
    title: 'AIRRAL | Start Your Job Search in the Applicant Portal',
    description:
      'Create an AIRRAL applicant account to save roles, improve matching, upload your resume, and apply with context.',
    path: '/apply',
  },
  employers: {
    title: 'AIRRAL | Applicant Tracking System for Lean Hiring Teams',
    description:
      'Post jobs, collect applications, review candidates, schedule interviews, and manage hiring in AIRRAL.',
    path: '/for-employers',
  },
  pricing: {
    title: 'AIRRAL | Pricing for Job Seekers and Hiring Teams',
    description:
      'AIRRAL is free for job seekers. Employers can start with Quick Hire or upgrade for advanced hiring workflows.',
    path: '/pricing',
    structuredData: [
      {
        '@context': 'https://schema.org',
        '@type': 'FAQPage',
        mainEntity: [
          {
            '@type': 'Question',
            name: 'Is AIRRAL free for job seekers?',
            acceptedAnswer: {
              '@type': 'Answer',
              text: 'Yes. Job seekers can browse jobs, apply, and track applications free.',
            },
          },
          {
            '@type': 'Question',
            name: 'Is there a free trial for companies?',
            acceptedAnswer: {
              '@type': 'Answer',
              text: 'Quick Hire is free with limited active jobs. Professional and Enterprise plans are available for teams that need deeper hiring workflows.',
            },
          },
        ],
      },
    ],
  },
  howItWorks: {
    title: 'AIRRAL | How Jobs, Fit Signals, Rooms, and Applications Work',
    description:
      'Learn how AIRRAL helps candidates find better roles, check context, ask rooms, and apply with momentum.',
    path: '/how-it-works',
  },
  about: {
    title: 'AIRRAL | About Fairer, Faster, More Human Hiring',
    description:
      'AIRRAL is building a job-search workspace and hiring platform focused on better roles, clearer context, and fairer hiring.',
    path: '/about',
  },
  contact: {
    title: 'AIRRAL | Contact Support and Sales',
    description:
      'Contact AIRRAL for applicant support, employer sales, hiring platform questions, or partnership requests.',
    path: '/contact',
  },
  help: {
    title: 'AIRRAL | Help Center for Applicants and Employers',
    description:
      'Get help with AIRRAL jobs, applications, applicant accounts, employer tools, pricing, and platform support.',
    path: '/help',
  },
  blog: {
    title: 'AIRRAL | Blog for Job Search and Hiring',
    description:
      'Read AIRRAL updates and practical guidance on job search strategy, hiring workflows, and applicant experience.',
    path: '/blog',
  },
  login: {
    title: 'Sign In | AIRRAL',
    description: 'Sign in to AIRRAL to manage your applicant portal, saved jobs, rooms, applications, or employer workspace.',
    path: '/login',
    robots: 'noindex, follow',
  },
  signup: {
    title: 'Create an AIRRAL Account | Applicant and Employer Access',
    description: 'Create an AIRRAL account to start your job search or manage hiring workflows.',
    path: '/sign-up',
    robots: 'noindex, follow',
  },
  terms: {
    title: 'Terms of Service | AIRRAL',
    description: 'Read the AIRRAL terms of service for applicants, employers, and platform users.',
    path: '/terms',
  },
  privacy: {
    title: 'Privacy Policy | AIRRAL',
    description: 'Read how AIRRAL handles applicant, employer, and platform data.',
    path: '/privacy',
  },
  cookies: {
    title: 'Cookie Policy | AIRRAL',
    description: 'Read how AIRRAL uses cookies and similar technologies.',
    path: '/cookies',
  },
};
