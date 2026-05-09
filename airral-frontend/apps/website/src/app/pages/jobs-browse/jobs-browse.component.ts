import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { JobApiService } from '@airral/shared-api';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';

@Component({
  selector: 'app-jobs-browse',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './jobs-browse.component.html',
  styleUrls: ['./jobs-browse.component.css'],
})
export class JobsBrowseComponent implements OnInit {
  jobs: any[] = [];
  filteredJobs: any[] = [];
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

  constructor(private jobService: JobApiService) {}

  ngOnInit() {
    this.loadJobs();
  }

  loadJobs() {
    this.loading = true;
    this.jobService.getOpenJobs().subscribe({
      next: (jobs) => {
        this.jobs = jobs;
        this.applyFilters();
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to load jobs';
        this.loading = false;
      },
    });
  }

  applyFilters() {
    this.filteredJobs = this.jobs.filter((job) => {
      const matchesDept = this.activeDepartment === 'All' || job.department === this.activeDepartment;
      const matchesSearch = this.searchQuery === '' || job.title.toLowerCase().includes(this.searchQuery.toLowerCase());
      return matchesDept && matchesSearch;
    });
  }

  selectDepartment(dept: string) {
    this.activeDepartment = dept;
    this.applyFilters();
  }

  onSearchChange() {
    this.applyFilters();
  }

  get departments(): string[] {
    const depts = new Set(this.jobs.map((j) => j.department));
    return ['All', ...Array.from(depts)];
  }
}
