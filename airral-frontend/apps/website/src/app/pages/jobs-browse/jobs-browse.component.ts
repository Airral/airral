import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { JobApiService } from '@airral/shared-api';
import { Job } from '@airral/shared-types';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { PAGE_SEO } from '../../shared/seo-pages';
import { SeoService } from '../../shared/seo.service';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';

@Component({
  selector: 'app-jobs-browse',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './jobs-browse.component.html',
  styleUrls: ['./jobs-browse.component.css'],
})
export class JobsBrowseComponent implements OnInit {
  jobs: Job[] = [];
  filteredJobs: Job[] = [];
  searchQuery = '';
  activeDepartment = 'All';
  loading = true;
  error: string | null = null;

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  readonly headerConfig = {
    brand: 'AIRRAL',
    tagline: 'Browse Jobs',
    links: [
      { label: 'Home', path: '/' },
      { label: 'How It Works', path: '/how-it-works' },
      { label: 'About', path: '/about' },
    ],
    ctas: [
      { label: 'For Employers', path: '/for-employers', external: false },
    ],
  };

  readonly footerConfig = {
    brand: 'AIRRAL',
    tagline: 'Fair hiring for everyone.',
    columns: [
      {
        title: 'Product',
        links: [
          { label: 'For Candidates', path: '/' },
          { label: 'For Employers', path: '/for-employers' },
          { label: 'Pricing', path: '/pricing' },
        ],
      },
      {
        title: 'Company',
        links: [
          { label: 'About Us', path: '/about' },
          { label: 'Contact', path: '/contact' },
          { label: 'Blog', path: '/blog' },
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
    ],
  };

  constructor(
    private jobService: JobApiService,
    private route: ActivatedRoute,
    private seo: SeoService
  ) {}

  ngOnInit() {
    this.route.queryParamMap.subscribe((params) => {
      this.searchQuery = params.get('q') || params.get('search') || '';
      this.applyFilters();
    });

    this.route.data.subscribe((data) => {
      const resolvedJobs = data['jobs'] as Job[] | undefined;
      if (resolvedJobs) {
        this.jobs = resolvedJobs;
        this.loading = false;
        this.error = null;
        this.applyFilters();
        return;
      }

      this.loadJobs();
    });
  }

  loadJobs() {
    this.loading = true;
    void this.loadJobsForRender();
  }

  private async loadJobsForRender(): Promise<void> {
    const jobs = await firstValueFrom(
      this.jobService.getOpenJobs({
        query: this.searchQuery,
        department: this.activeDepartment,
      }).pipe(
        timeout(2500),
        catchError(() => of(null))
      )
    );

    if (!jobs) {
      this.error = 'Failed to load jobs';
      this.loading = false;
      return;
    }

    this.jobs = jobs;
    this.applyFilters();
    this.loading = false;
  }

  applyFilters() {
    const query = this.searchQuery.trim().toLowerCase();
    this.filteredJobs = this.jobs.filter((job) => {
      const matchesDept = this.activeDepartment === 'All' || job.department === this.activeDepartment;
      const matchesSearch =
        !query ||
        job.title.toLowerCase().includes(query) ||
        (job.description || '').toLowerCase().includes(query) ||
        (job.department || '').toLowerCase().includes(query) ||
        (job.location || '').toLowerCase().includes(query);
      return matchesDept && matchesSearch;
    });
    this.updateJobsSeo();
  }

  selectDepartment(dept: string) {
    this.activeDepartment = dept;
    this.loadJobs();
  }

  onSearchChange() {
    this.applyFilters();
  }

  get departments(): string[] {
    const depts = new Set(this.jobs.map((j) => j.department).filter(Boolean) as string[]);
    return ['All', ...Array.from(depts)];
  }

  private updateJobsSeo(): void {
    const query = this.searchQuery.trim();
    const title = query
      ? `AIRRAL | ${query} Jobs and Career Roles`
      : PAGE_SEO['jobs'].title;
    const description = query
      ? `Browse ${query} jobs on AIRRAL. Compare role fit, salary context, source quality, and apply through the applicant portal.`
      : PAGE_SEO['jobs'].description;
    const path = query ? `/jobs?q=${encodeURIComponent(query)}` : '/jobs';

    this.seo.setPage({
      ...PAGE_SEO['jobs'],
      title,
      description,
      path,
      structuredData: [
        {
          '@context': 'https://schema.org',
          '@type': 'CollectionPage',
          name: title,
          description,
          mainEntity: {
            '@type': 'ItemList',
            itemListElement: this.filteredJobs.slice(0, 20).map((job, index) => ({
              '@type': 'ListItem',
              position: index + 1,
              url: `https://www.airral.com/jobs/${job.id}`,
              name: job.title,
            })),
          },
        },
      ],
    });
  }
}
