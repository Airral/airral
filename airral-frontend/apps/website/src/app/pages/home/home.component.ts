// apps/website/src/app/pages/home/home.component.ts
import { AfterViewInit, Component, ElementRef, NgZone, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { FooterComponent, HeaderComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { Job } from '@airral/shared-types';
import { JobApiService } from '@airral/shared-api';

interface Feature {
  icon: string;
  title: string;
  description: string;
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
  imports: [
    CommonModule,
    RouterModule,
    FormsModule,
    HeaderComponent,
    FooterComponent,
  ],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.css'],
})
export class HomeComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  jobs: Job[] = [];
  filteredJobs: Job[] = [];
  loading = false;
  error: string | null = null;
  searchQuery = '';
  activeDepartment = 'All';

  // Partner logos - replace with real client logos when available
  // For now, removed to avoid misrepresentation
  readonly partnerLogos: string[] = [];

  readonly features: Feature[] = [
    {
      icon: '⚡',
      title: 'Launch hiring in one workspace',
      description:
        'Post roles, collect applicants, and keep every candidate in one owner-friendly view.',
    },
    {
      icon: '◎',
      title: 'Know what needs attention',
      description:
        'See new applicants, interviews, offer work, and stuck candidates without living in spreadsheets.',
    },
    {
      icon: '◉',
      title: 'Collaborate before you have recruiting ops',
      description:
        'Invite owners, managers, and interviewers into a simple review flow with clear ownership.',
    },
    {
      icon: '🔒',
      title: 'Respect candidates from day one',
      description:
        'Give applicants status visibility and a cleaner experience than the usual hiring black hole.',
    },
  ];

  readonly processSteps: ProcessStep[] = [
    {
      title: 'Post your first role',
      description:
        'Create a business-ready job page and start collecting applicants without a long setup project.',
    },
    {
      title: 'Review as a team',
      description:
        'Move candidates through a shared pipeline so every reviewer knows what to do next.',
    },
    {
      title: 'Run interviews with context',
      description:
        'Schedule interviews, collect feedback, and keep notes tied to the candidate record.',
    },
    {
      title: 'Close the loop',
      description:
        'Send offers, update statuses, and keep candidates informed whether it is a yes or a no.',
    },
  ];

  // Testimonials - will be populated with real customer feedback
  // Removed placeholder testimonials to maintain authenticity
  readonly testimonials: Testimonial[] = [];

  readonly faqs: FaqItem[] = [
    {
      question: 'Can a startup or small business use AIRRAL before hiring a recruiter?',
      answer:
        'Yes. Quick Hire is built for founders, owners, operators, and first hiring managers who need a real process before they have a recruiting team.',
    },
    {
      question: 'What does the free workspace include?',
      answer:
        'Quick Hire includes a useful workspace for up to 5 active jobs and 3 teammates, with basic applicant tracking and candidate profiles.',
    },
    {
      question: 'Is AIRRAL still free for candidates?',
      answer:
        'Yes. Candidates can browse jobs, apply, and track applications for free.',
    },
    {
      question: 'What changes when we upgrade?',
      answer:
        'Professional adds advanced pipeline views, analytics, larger team workflows, and hiring controls for teams that are scaling.',
    },
  ];

  private scrollModelObserver?: IntersectionObserver;

  constructor(
    private jobService: JobApiService,
    private router: Router,
    private elementRef: ElementRef<HTMLElement>,
    private ngZone: NgZone
  ) {}

  ngOnInit(): void {
    this.loadJobs();
  }

  ngAfterViewInit(): void {
    this.setupScrollModelEffects();
  }

  ngOnDestroy(): void {
    this.scrollModelObserver?.disconnect();
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

  private setupScrollModelEffects(): void {
    const cards = this.getScrollModelCards();

    if (!cards.length) {
      return;
    }

    if (typeof window === 'undefined' || !('IntersectionObserver' in window)) {
      cards.forEach((card) => card.classList.add('is-visible'));
      return;
    }

    this.ngZone.runOutsideAngular(() => {
      this.scrollModelObserver = new IntersectionObserver(
        (entries) => {
          entries.forEach((entry) => {
            const card = entry.target as HTMLElement;

            if (entry.isIntersecting) {
              card.classList.add('is-visible');
              card.classList.toggle('is-active', entry.intersectionRatio >= 0.56);
              return;
            }

            card.classList.remove('is-active');
          });
        },
        {
          rootMargin: '-12% 0px -20%',
          threshold: [0.18, 0.42, 0.56, 0.74],
        }
      );

      cards.forEach((card, index) => {
        card.style.setProperty('--scroll-delay', `${index * 90}ms`);
        this.scrollModelObserver?.observe(card);
      });
    });
  }

  private getScrollModelCards(): HTMLElement[] {
    return Array.from(
      this.elementRef.nativeElement.querySelectorAll<HTMLElement>('.scroll-model-card')
    );
  }

  viewJob(jobId: number): void {
    this.router.navigate(['/jobs', jobId]);
  }

  applyJob(jobId: number): void {
    // Navigate to apply page with job ID as a query param
    this.router.navigate(['/apply'], { queryParams: { jobId } });
  }
}
