import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { JobApiService } from '@airral/shared-api';
import { Job } from '@airral/shared-types';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { SeoService } from '../../shared/seo.service';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';

@Component({
  selector: 'app-job-detail',
  standalone: true,
  imports: [CommonModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './job-detail.component.html',
  styleUrls: ['./job-detail.component.css'],
})
export class JobDetailComponent implements OnInit {
  job: Job | null = null;
  loading = true;
  error: string | null = null;
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantLoginUrl = `${PORTAL_ROUTES.APPLICANT}/login`;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  constructor(
    private route: ActivatedRoute,
    private jobService: JobApiService,
    private seo: SeoService
  ) {}

  ngOnInit(): void {
    const resolvedJob = this.route.snapshot.data['job'] as Job | null | undefined;
    if (resolvedJob === null) {
      this.showNotFound();
      return;
    }

    if (resolvedJob) {
      this.job = resolvedJob;
      this.loading = false;
      this.updateJobSeo(resolvedJob);
      return;
    }

    const id = Number(this.route.snapshot.paramMap.get('id'));
    if (!Number.isFinite(id)) {
      this.showNotFound();
      return;
    }

    void this.loadJobForRender(id);
  }

  private async loadJobForRender(id: number): Promise<void> {
    const job = await firstValueFrom(
      this.jobService.getJobById(id).pipe(
        timeout(2500),
        catchError(() => of(null))
      )
    );

    if (!job) {
      this.showNotFound();
      return;
    }

    this.job = job;
    this.loading = false;
    this.updateJobSeo(job);
  }

  getSalaryLabel(job: Job): string {
    if (job.salaryMin && job.salaryMax) {
      const formatter = new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
        maximumFractionDigits: 0,
      });
      return `${formatter.format(job.salaryMin)} - ${formatter.format(job.salaryMax)}`;
    }
    return 'Salary not listed';
  }

  getEmploymentType(job: Job): string {
    return job.employmentType || 'Full-time';
  }

  getRequirements(job: Job): string[] {
    return this.splitText(job.requirements || '');
  }

  getBenefits(job: Job): string[] {
    return this.splitText(job.niceToHave || '');
  }

  private updateJobSeo(job: Job): void {
    const location = job.location ? ` in ${job.location}` : '';
    const department = job.department ? `${job.department} role` : 'open role';
    const jobPosting = this.buildJobPostingSchema(job);
    this.seo.setPage({
      title: `AIRRAL | ${job.title}${location}`,
      description: `View the ${job.title} ${department} on AIRRAL. Check role details, requirements, salary context, and apply through the applicant portal.`,
      path: `/jobs/${job.id}`,
      type: 'article',
      structuredData: jobPosting ? [jobPosting] : [],
    });
  }

  private buildJobPostingSchema(job: Job): Record<string, unknown> | null {
    if (!job.title || !job.description || !job.createdAt) {
      return null;
    }

    const isRemote = /remote/i.test(job.location || '');
    const schema: Record<string, unknown> = {
      '@context': 'https://schema.org',
      '@type': 'JobPosting',
      title: job.title,
      description: this.toJobDescriptionHtml(job),
      datePosted: job.createdAt,
      employmentType: this.toSchemaEmploymentType(job.employmentType),
      hiringOrganization: {
        '@type': 'Organization',
        name: job.organizationName || 'confidential',
        ...(job.organizationDomain ? { sameAs: this.toCompanyUrl(job.organizationDomain) } : {}),
        ...(job.organizationLogoUrl ? { logo: this.toAbsoluteUrl(job.organizationLogoUrl) } : {}),
      },
      identifier: {
        '@type': 'PropertyValue',
        name: job.organizationName || 'AIRRAL',
        value: String(job.id),
      },
      directApply: true,
      url: `https://www.airral.com/jobs/${job.id}`,
    };

    if (job.location) {
      schema['jobLocation'] = {
        '@type': 'Place',
        address: {
          '@type': 'PostalAddress',
          addressLocality: job.location,
          addressCountry: 'US',
        },
      };
    }

    if (isRemote) {
      schema['jobLocationType'] = 'TELECOMMUTE';
      schema['applicantLocationRequirements'] = {
        '@type': 'Country',
        name: 'United States',
      };
    }

    if (job.salaryMin && job.salaryMax) {
      schema['baseSalary'] = {
        '@type': 'MonetaryAmount',
        currency: 'USD',
        value: {
          '@type': 'QuantitativeValue',
          minValue: job.salaryMin,
          maxValue: job.salaryMax,
          unitText: 'YEAR',
        },
      };
    }

    return schema;
  }

  private showNotFound(): void {
    this.job = null;
    this.loading = false;
    this.error = 'This job is no longer available.';
    this.seo.setPage({
      title: 'Job Not Available | AIRRAL Jobs',
      description: 'This AIRRAL job is no longer available. Browse current open roles and apply with context.',
      path: '/jobs',
      robots: 'noindex, follow',
    });
  }

  private splitText(value: string): string[] {
    return value
      .split(/\n|;|•|-/)
      .map((item) => item.trim())
      .filter(Boolean)
      .slice(0, 8);
  }

  private toJobDescriptionHtml(job: Job): string {
    const requirements = this.getRequirements(job).map((item) => `<li>${this.escapeHtml(item)}</li>`).join('');
    const details = [
      `<p>${this.escapeHtml(job.description)}</p>`,
      requirements ? `<ul>${requirements}</ul>` : '',
    ];
    return details.filter(Boolean).join('');
  }

  private toSchemaEmploymentType(value?: string): string {
    const normalized = (value || '').toLowerCase();
    if (normalized.includes('part')) return 'PART_TIME';
    if (normalized.includes('contract')) return 'CONTRACTOR';
    if (normalized.includes('intern')) return 'INTERN';
    if (normalized.includes('temporary')) return 'TEMPORARY';
    return 'FULL_TIME';
  }

  private toAbsoluteUrl(value: string): string {
    if (/^https?:\/\//i.test(value)) {
      return value;
    }
    if (value.startsWith('/')) {
      return `https://www.airral.com${value}`;
    }
    return `https://${value.replace(/^\/\//, '')}`;
  }

  private toCompanyUrl(value: string): string {
    if (/^https?:\/\//i.test(value)) {
      return value;
    }
    return `https://${value}`;
  }

  private escapeHtml(value: string): string {
    return value
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }
}
