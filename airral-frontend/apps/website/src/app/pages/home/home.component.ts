// apps/website/src/app/pages/home/home.component.ts
import { Component, Inject, OnInit, PLATFORM_ID } from '@angular/core';
import { CommonModule, isPlatformBrowser } from '@angular/common';
import { Router, RouterModule } from '@angular/router';
import { FormsModule } from '@angular/forms';
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

interface PreviewSignal {
  label: string;
  value: string;
}

interface PreviewJob {
  mark: string;
  listTitle: string;
  listMeta: string;
  match: string;
  source: string;
  title: string;
  location: string;
  slug: string;
  context: string;
  signals: PreviewSignal[];
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
export class HomeComponent implements OnInit {
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;

  jobs: Job[] = [];
  filteredJobs: Job[] = [];
  loading = false;
  error: string | null = null;
  searchQuery = '';
  activeDepartment = 'All';
  selectedPreviewIndex = 0;

  targetHeroText = 'Find roles with color, context, and momentum. AIRRAL brings real jobs, warm rooms, resume fit, salary signal, and saved next steps into one applicant workspace.';
  displayedHeroText = this.targetHeroText;

  // Partner logos - replace with real client logos when available
  // For now, removed to avoid misrepresentation
  readonly partnerLogos: string[] = [];

  readonly features: Feature[] = [
    {
      icon: '01',
      title: 'Real roles before noise',
      description:
        'Start from fresh jobs and source context instead of another endless board full of stale listings.',
    },
    {
      icon: '02',
      title: 'Fit before you apply',
      description:
        'See salary, location, work mode, source, and match signals before spending time on an application.',
    },
    {
      icon: '03',
      title: 'Warm context around every job',
      description:
        'Use rooms to ask about the company, role, interview loop, and people who can help.',
    },
    {
      icon: '04',
      title: 'One job-search workspace',
      description:
        'Track jobs, resume checks, events, and follow-up from one applicant portal instead of scattered tabs.',
    },
  ];

  readonly processSteps: ProcessStep[] = [
    {
      title: 'Choose a role worth your time',
      description:
        'AIRRAL puts job quality, freshness, salary clarity, and fit cues next to the listing.',
    },
    {
      title: 'Check the path before applying',
      description:
        'See whether the role has a warm path, room context, company signal, or resume gap to fix first.',
    },
    {
      title: 'Apply with context',
      description:
        'Move into your applicant portal with the role, notes, room, and next action attached.',
    },
    {
      title: 'Keep momentum',
      description:
        'Use job rooms, events, resume checks, and saved roles to keep the search organized.',
    },
  ];

  readonly previewJobs: PreviewJob[] = [
    {
      mark: 'D',
      listTitle: 'Frontend Web Developer',
      listMeta: 'DoorDash · Remote',
      match: '92% match',
      source: 'Official source · Greenhouse',
      title: 'Frontend Web Developer, B2B Marketing Technology',
      location: 'San Francisco, CA; New York, NY; Washington D.C.; United States - Remote',
      slug: 'frontend-web-developer',
      context: 'Quick read, resume fit, room help, and warm context stay attached to the selected role.',
      signals: [
        { label: 'Salary', value: 'USD $109k-$160k' },
        { label: 'Work mode', value: 'Remote' },
        { label: 'Posted', value: 'Just updated' },
      ],
    },
    {
      mark: 'A',
      listTitle: 'Senior Talent Partner',
      listMeta: 'Airbnb · Hybrid',
      match: '78% match',
      source: 'Official source · Lever',
      title: 'Senior Talent Partner, Marketplace Growth',
      location: 'New York, NY; San Francisco, CA - Hybrid',
      slug: 'senior-talent-partner',
      context: 'See hiring-team context, likely interview loop, resume gaps, and warm room signals before applying.',
      signals: [
        { label: 'Salary', value: 'USD $132k-$178k' },
        { label: 'Work mode', value: 'Hybrid' },
        { label: 'Posted', value: '2 days ago' },
      ],
    },
    {
      mark: 'R',
      listTitle: 'AI Partnerships Manager',
      listMeta: 'Ramp · Remote',
      match: '78% match',
      source: 'Official source · Ashby',
      title: 'AI Partnerships Manager, Financial Products',
      location: 'United States - Remote',
      slug: 'ai-partnerships-manager',
      context: 'Compare role signal, company momentum, room questions, and application readiness in one place.',
      signals: [
        { label: 'Salary', value: 'USD $145k-$210k' },
        { label: 'Work mode', value: 'Remote' },
        { label: 'Posted', value: 'This week' },
      ],
    },
  ];

  // Testimonials - will be populated with real customer feedback
  // Removed placeholder testimonials to maintain authenticity
  readonly testimonials: Testimonial[] = [];

  readonly faqs: FaqItem[] = [
    {
      question: 'Is AIRRAL mainly for job seekers now?',
      answer:
        'Yes. The public website now points candidates into the applicant portal first. Employer tools still exist, but the top-level story is helping people find and apply to better roles.',
    },
    {
      question: 'What happens after I create a candidate account?',
      answer:
        'You land in the applicant portal, where jobs open first. From there you can improve matching, open job details, ask rooms, save roles, and attach resume checks to target jobs.',
    },
    {
      question: 'Is AIRRAL another job board?',
      answer:
        'No. AIRRAL should behave like a job-search operating system: jobs are the entry point, but the advantage is fit, source context, rooms, events, and warm paths around each role.',
    },
    {
      question: 'Do companies still use AIRRAL?',
      answer:
        'Yes. HR and admin apps remain available for companies. Focusing on applicants first helps AIRRAL build better candidate data and a stronger marketplace before selling deeper employer workflows.',
    },
  ];

  constructor(
    private jobService: JobApiService,
    private router: Router,
    @Inject(PLATFORM_ID) private platformId: object
  ) {}

  ngOnInit(): void {
    // SSR safe: keep the hero copy stable for prerender and hydration.
    this.displayedHeroText = this.targetHeroText;

    if (isPlatformBrowser(this.platformId)) {
      setTimeout(() => this.loadJobs());
    }
  }

  get selectedPreviewJob(): PreviewJob {
    return this.previewJobs[this.selectedPreviewIndex] ?? this.previewJobs[0];
  }

  selectPreviewJob(index: number): void {
    this.selectedPreviewIndex = index;
  }

  onPreviewMouseMove(event: MouseEvent): void {
    const el = event.currentTarget as HTMLElement;
    const rect = el.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const y = event.clientY - rect.top;
    // Pass coordinates to CSS for dynamic glow effects
    el.style.setProperty('--mouse-x', `${x}px`);
    el.style.setProperty('--mouse-y', `${y}px`);
  }

  improvePreviewMatches(): void {
    this.router.navigate(['/apply'], { queryParams: { focus: 'matches' } });
  }

  loadJobs(): void {
    this.loading = true;
    this.error = null;
    this.jobService.getOpenJobs({
      query: this.searchQuery,
      department: this.activeDepartment,
    }).subscribe({
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
    this.loadJobs();
  }

  onSearchChange(): void {
    this.applyFilters();
  }

  searchJobs(): void {
    this.loadJobs();
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

}
