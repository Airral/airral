import { Route } from '@angular/router';
import { jobDetailResolver, openJobsResolver } from './shared/job-route.resolvers';
import { PAGE_SEO } from './shared/seo-pages';

export const appRoutes: Route[] = [
  {
    path: '',
    data: { seo: PAGE_SEO['home'] },
    loadComponent: () =>
      import('./pages/home/home.component').then((m) => m.HomeComponent),
  },
  {
    path: 'login',
    data: { seo: PAGE_SEO['login'] },
    loadComponent: () =>
      import('./pages/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'apply',
    data: { seo: PAGE_SEO['apply'] },
    loadComponent: () =>
      import('./pages/apply/apply.component').then((m) => m.ApplyComponent),
  },
  {
    path: 'sign-up',
    data: { seo: PAGE_SEO['signup'] },
    loadComponent: () =>
      import('./pages/sign-up/sign-up.component').then((m) => m.SignUpComponent),
  },
  {
    path: 'about',
    data: { seo: PAGE_SEO['about'] },
    loadComponent: () =>
      import('./pages/about/about.component').then((m) => m.AboutComponent),
  },
  {
    path: 'contact',
    data: { seo: PAGE_SEO['contact'] },
    loadComponent: () =>
      import('./pages/contact/contact.component').then((m) => m.ContactComponent),
  },
  {
    path: 'for-employers',
    data: { seo: PAGE_SEO['employers'] },
    loadComponent: () =>
      import('./pages/for-employers/for-employers.component').then((m) => m.ForEmployersComponent),
  },
  {
    path: 'how-it-works',
    data: { seo: PAGE_SEO['howItWorks'] },
    loadComponent: () =>
      import('./pages/how-it-works/how-it-works.component').then((m) => m.HowItWorksComponent),
  },
  {
    path: 'pricing',
    data: { seo: PAGE_SEO['pricing'] },
    loadComponent: () =>
      import('./pages/pricing/pricing.component').then((m) => m.PricingComponent),
  },
  {
    path: 'jobs',
    data: { seo: PAGE_SEO['jobs'] },
    resolve: { jobs: openJobsResolver },
    loadComponent: () =>
      import('./pages/jobs-browse/jobs-browse.component').then((m) => m.JobsBrowseComponent),
  },
  {
    path: 'jobs/:id',
    data: { seo: PAGE_SEO['jobs'] },
    resolve: { job: jobDetailResolver },
    loadComponent: () =>
      import('./pages/job-detail/job-detail.component').then((m) => m.JobDetailComponent),
  },
  {
    path: 'help',
    data: { seo: PAGE_SEO['help'] },
    loadComponent: () =>
      import('./pages/help/help.component').then((m) => m.HelpComponent),
  },
  {
    path: 'blog',
    data: { seo: PAGE_SEO['blog'] },
    loadComponent: () =>
      import('./pages/blog/blog.component').then((m) => m.BlogComponent),
  },
  {
    path: 'terms',
    data: { seo: PAGE_SEO['terms'] },
    loadComponent: () =>
      import('./pages/legal/legal.component').then((m) => m.LegalComponent),
  },
  {
    path: 'privacy',
    data: { seo: PAGE_SEO['privacy'] },
    loadComponent: () =>
      import('./pages/legal/legal.component').then((m) => m.LegalComponent),
  },
  {
    path: 'cookies',
    data: { seo: PAGE_SEO['cookies'] },
    loadComponent: () =>
      import('./pages/legal/legal.component').then((m) => m.LegalComponent),
  },
  { path: '**', redirectTo: '' },
];
