// apps/website/src/app/pages/home/home.component.ts
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { FooterComponent, HeaderComponent, HeaderNavLink, HeaderCta } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { Job } from '@airral/shared-types';
import { JobApiService } from '@airral/shared-api';

interface Feature {
  icon: string;
  title: string;
  description: string;
}

interface Stat {
  value: string;
  label: string;
}

interface ProcessStep {
  title: string;
  description: string;
}

interface Testimonial {
  quote: string;
  name: string;
  role: string;
}

interface FaqItem {
  question: string;
  answer: string;
}

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, RouterModule, FormsModule, HeaderComponent, FooterComponent],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
})
export class HomeComponent implements OnInit {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  jobs: Job[] = [];
  filteredJobs: Job[] = [];
  loading = false;
  error: string | null = null;
  searchQuery = '';
  activeDepartment = 'All';

  // Real, verifiable stats - conservative approach
  readonly stats: Stat[] = [
    { value: '100%', label: 'Free for job seekers' },
    { value: '24/7', label: 'Application tracking' },
    { value: 'Real-time', label: 'Status updates' },
    { value: 'Unlimited', label: 'Job applications' },
  ];

  // Partner logos - replace with real client logos when available
  // For now, removed to avoid misrepresentation
  readonly partnerLogos: string[] = [];

  readonly features: Feature[] = [
    {
      icon: '⚡',
      title: 'One-click applications',
      description:
        'Apply to dozens of great roles with a single profile. No repetitive forms, ever.',
    },
    {
      icon: '📊',
      title: 'Real-time tracking',
      description:
        'See where you stand at every stage — from submission to offer.',
    },
    {
      icon: '🤝',
      title: 'Direct hiring manager chat',
      description:
        'Skip the black hole. Talk to the people you would actually work with.',
    },
    {
      icon: '🔒',
      title: 'Privacy-first',
      description:
        'You control who sees your profile. Stay stealthy while you explore.',
    },
  ];

  readonly processSteps: ProcessStep[] = [
    {
      title: 'Create your profile',
      description:
        'Build one profile once, then apply to multiple roles without repeating your details.',
    },
    {
      title: 'Match and apply',
      description:
        'Use smart search and filters to find high-fit jobs by title, department and skills.',
    },
    {
      title: 'Track in real time',
      description:
        'Get clear status updates from submitted to interview, with no guesswork.',
    },
    {
      title: 'Interview and decide',
      description:
        'Move through interviews faster and make confident decisions with transparent communication.',
    },
  ];

  // Testimonials - will be populated with real customer feedback
  // Removed placeholder testimonials to maintain authenticity
  readonly testimonials: Testimonial[] = [];

  readonly faqs: FaqItem[] = [
    {
      question: 'Is AIRRAL free for candidates?',
      answer:
        'Yes. Creating a profile, searching jobs, and applying to roles is free for all candidates.',
    },
    {
      question: 'Can I track my application status?',
      answer:
        'Yes. Every role has live status updates so you can see exactly where your application stands.',
    },
    {
      question: 'How fast do companies usually respond?',
      answer:
        'Most active roles receive first responses within 48 hours, depending on hiring volume.',
    },
    {
      question: 'Can hiring teams use AIRRAL too?',
      answer:
        'Absolutely. Teams can post jobs, review applicants, and move candidates through the pipeline from one dashboard.',
    },
  ];

  constructor(
    private jobService: JobApiService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadJobs();
  }

  loadJobs(): void {
    this.loading = true;
    this.jobService.getOpenJobs().subscribe({
      next: (data) => {
        this.jobs = data ?? [];
        this.applyFilters();
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load jobs';
        this.loading = false;
      },
    });
  }

  get departments(): string[] {
    const set = new Set<string>();
    this.jobs.forEach((j) => j.department && set.add(j.department));
    return ['All', ...Array.from(set).sort()];
  }

  selectDepartment(dep: string): void {
    this.activeDepartment = dep;
    this.applyFilters();
  }

  onSearchChange(): void {
    this.applyFilters();
  }

  private applyFilters(): void {
    const q = this.searchQuery.trim().toLowerCase();
    this.filteredJobs = this.jobs.filter((job) => {
      const matchesDep =
        this.activeDepartment === 'All' || job.department === this.activeDepartment;
      const matchesQuery =
        !q ||
        job.title.toLowerCase().includes(q) ||
        (job.description ?? '').toLowerCase().includes(q) ||
        (job.department ?? '').toLowerCase().includes(q);
      return matchesDep && matchesQuery;
    });
  }

  viewJob(jobId: number): void {
    this.router.navigate(['/jobs', jobId]);
  }

  applyJob(jobId: number): void {
    // Navigate to apply page with job ID as a query param
    this.router.navigate(['/apply'], { queryParams: { jobId } });
  }
}

