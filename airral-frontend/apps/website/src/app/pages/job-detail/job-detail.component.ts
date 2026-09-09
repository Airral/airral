import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { Job } from '@airral/shared-types';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { SeoService } from '../../shared/seo.service';
import { jobDetailPath, ResolvedJob } from '../../shared/job-route.resolvers';

/**
 * schema.org unitText for each interval we persist. Anything not listed here --
 * including a posting whose board never stated an interval -- gets no baseSalary
 * rather than a guessed one.
 */
const SCHEMA_SALARY_UNITS: Record<string, string> = {
  YEAR: 'YEAR',
  MONTH: 'MONTH',
  WEEK: 'WEEK',
  DAY: 'DAY',
  HOUR: 'HOUR',
};

/**
 * How each interval reads next to an amount on the page itself.
 *
 * <p>The label used to be a bare "$40 - $40" while the description two inches
 * below said "Hourly Rate", so the page contradicted itself and a visitor read
 * an annual figure. An interval missing from this map means the source never
 * stated one, and the bare amount is shown rather than an implied year.
 *
 * <p>Wider than SCHEMA_SALARY_UNITS on purpose: ONE_TIME is a real thing a
 * board tells us and a reader understands, but schema.org's unitText has no
 * value for it, so it is spelled out here and left out of the JSON-LD.
 */
const SALARY_PERIOD_SUFFIXES: Record<string, string> = {
  YEAR: '/yr',
  MONTH: '/mo',
  WEEK: '/wk',
  DAY: '/day',
  HOUR: '/hr',
  ONE_TIME: ' total',
};

/** ISO 4217 shape. Intl.NumberFormat throws on anything else, mid-render. */
const CURRENCY_CODE = /^[A-Za-z]{3}$/;

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
  /** The API did not answer, as opposed to answering that the posting is gone. */
  unavailable = false;
  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantLoginUrl = `${PORTAL_ROUTES.APPLICANT}/login`;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  constructor(
    private route: ActivatedRoute,
    private seo: SeoService
  ) {}

  /**
   * Everything this page renders comes from the route resolver, which has
   * already completed by the time this runs.
   *
   * <p>There is deliberately no fetch fallback here any more. An HTTP call
   * started in ngOnInit resolves after the server has emitted its HTML, so
   * under SSR a crawler was handed a shell with no title, no canonical and no
   * JobPosting -- which is the entire thing this page exists to publish.
   */
  ngOnInit(): void {
    const resolved = this.route.snapshot.data['job'] as ResolvedJob | undefined;

    if (!resolved?.job) {
      if (resolved?.unavailable) {
        this.showUnavailable();
      } else {
        this.showGone();
      }
      return;
    }

    this.job = resolved.job;
    this.loading = false;
    this.updateJobSeo(resolved.job);
  }

  getSalaryLabel(job: Job): string {
    if (!job.salaryMin || !job.salaryMax) {
      return 'Salary not listed';
    }

    const formatter = new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: this.currencyCode(job),
      // Rounding is fine on a yearly band and wrong on an hourly one, where
      // $40.50 is not $41.
      maximumFractionDigits: job.salaryPeriod === 'HOUR' ? 2 : 0,
    });

    // Boards routinely post a single figure as both ends of the band, and
    // "$40 - $40" reads as an error rather than a rate.
    const amount = job.salaryMin === job.salaryMax
      ? formatter.format(job.salaryMin)
      : `${formatter.format(job.salaryMin)} - ${formatter.format(job.salaryMax)}`;

    return `${amount}${SALARY_PERIOD_SUFFIXES[job.salaryPeriod ?? ''] ?? ''}`;
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

  /**
   * Where a synced posting is really applied to.
   *
   * <p>The rail's two account CTAs were the whole apply path on this page, and
   * on a synced posting they lead nowhere: toJob gives every one of them id 0,
   * so the portal is handed jobId=0 and holds no record of the job under any
   * internal id. These are the eleven thousand URLs in the sitemap and the
   * landing page for organic job traffic, and buildJobPostingSchema already
   * tells Google directApply is false -- so the off-site form has to be on the
   * page the crawler indexed, not only on the browse card that links to it.
   */
  externalApply(job: Job): string | null {
    const j = job as Job & { externalApplyUrl?: string };
    return job.id ? null : j.externalApplyUrl || null;
  }

  /**
   * The account CTA's address, carrying the job only when there is one to carry.
   *
   * <p>A synced posting would append jobId=0. Nothing in the portal reads the
   * parameter today, and there is no internal row behind a 0 for it to read if
   * it ever did, so it is left off rather than published on every synced URL.
   */
  registerUrl(job: Job): string {
    return job.id ? `${this.applicantRegisterUrl}&jobId=${job.id}` : this.applicantRegisterUrl;
  }

  /** The same, for the sign-in link. */
  saveJobUrl(job: Job): string {
    return job.id ? `${this.applicantLoginUrl}?jobId=${job.id}` : this.applicantLoginUrl;
  }

  /**
   * The currency the amounts are quoted in, once it is safe to use.
   *
   * <p>These codes come from other companies' job boards. Intl.NumberFormat
   * throws a RangeError on anything that is not three letters, and it is called
   * from the template -- so a single malformed code would take down the whole
   * server render, not just the pay line.
   */
  private currencyCode(job: Job): string {
    return CURRENCY_CODE.test(job.salaryCurrency || '') ? (job.salaryCurrency as string) : 'USD';
  }

  /**
   * The address this visit asked for, taken from the route rather than from the
   * job -- so it is still right when there is no job to build it from.
   */
  private requestedPath(): string {
    const params = this.route.snapshot.paramMap;
    const source = params.get('source');
    const board = params.get('board');
    const externalId = params.get('externalId');

    if (source && board && externalId) {
      return `/jobs/${encodeURIComponent(source)}`
        + `/${encodeURIComponent(board)}`
        + `/${encodeURIComponent(externalId)}`;
    }

    const id = params.get('id');
    return id ? `/jobs/${encodeURIComponent(id)}` : '/jobs';
  }

  /** Whether this posting came from a company's own board rather than our ATS. */
  private isExternal(job: Job): boolean {
    const j = job as Job & { externalSource?: string; externalJobId?: string };
    return Boolean(j.externalSource && j.externalJobId);
  }

  /** The identifier a search engine should use to tell this posting from any other. */
  private jobIdentifier(job: Job): string {
    const j = job as Job & {
      externalSource?: string;
      externalBoardToken?: string;
      externalJobId?: string;
    };

    return this.isExternal(job)
      ? `${j.externalSource}:${j.externalBoardToken}:${j.externalJobId}`
      : String(job.id);
  }

  private updateJobSeo(job: Job): void {
    const location = job.location ? ` in ${job.location}` : '';
    const department = job.department ? `${job.department} role` : 'open role';
    const jobPosting = this.buildJobPostingSchema(job);
    this.seo.setPage({
      title: `AIRRAL | ${job.title}${location}`,
      description: `View the ${job.title} ${department} on AIRRAL. Check role details, requirements, salary context, and apply through the applicant portal.`,
      path: jobDetailPath(job),
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
        // A synced posting has no internal id, so this would be "0" on every one
        // of them -- and identifier is how a search engine tells two postings
        // apart. Its own external id is the stable, unique thing.
        value: this.jobIdentifier(job),
      },
      // Only true when the application can actually be completed here. A synced
      // posting sends the candidate to the employer's own form, and Google treats
      // a wrong directApply as a reason to distrust the listing.
      directApply: !this.isExternal(job),
      url: `https://www.airral.com${jobDetailPath(job)}`,
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

    // Only publish pay when the board told us the interval it was quoted in.
    // This block used to hardcode unitText 'YEAR', so an hourly intern rate went
    // to Google as a $50-a-year job. An amount whose unit we do not know is not
    // worth a rich result; omitting baseSalary loses a snippet, stating the
    // wrong one misinforms every reader who sees it.
    const salaryUnit = SCHEMA_SALARY_UNITS[job.salaryPeriod ?? ''];
    if (job.salaryMin && job.salaryMax && salaryUnit) {
      schema['baseSalary'] = {
        '@type': 'MonetaryAmount',
        currency: this.currencyCode(job),
        value: {
          '@type': 'QuantitativeValue',
          minValue: job.salaryMin,
          maxValue: job.salaryMax,
          unitText: salaryUnit,
        },
      };
    }

    // Without validThrough Google keeps a listing eligible for only a short
    // default window, so a posting drops out of job results while it is still
    // open. Emitted only from a date the API actually stated -- a guessed one
    // would expire live postings early, which is the failure this is meant to
    // avoid.
    const validThrough = this.statedExpiry(job);
    if (validThrough) {
      schema['validThrough'] = validThrough;
    }

    return schema;
  }

  /**
   * The expiry the API stated, or nothing.
   *
   * <p>external_job_postings.expires_at exists on the backend but the candidate
   * DTO does not serialise it yet, so this reads the field optimistically and
   * starts emitting the day the API sends it. Parsed before use because a value
   * schema.org cannot read is worse than an absent one -- Google reports the
   * whole JobPosting as invalid rather than ignoring the field.
   */
  private statedExpiry(job: Job): string | null {
    const value = (job as Job & { expiresAt?: string }).expiresAt;
    if (!value || Number.isNaN(Date.parse(value))) {
      return null;
    }
    return value;
  }

  /**
   * The posting is genuinely gone. Say so, and take this URL out of the index.
   */
  private showGone(): void {
    this.job = null;
    this.loading = false;
    this.unavailable = false;
    this.error = 'This job is no longer available.';
    this.seo.setPage({
      title: 'Job Not Available | AIRRAL Jobs',
      description: 'This AIRRAL job is no longer available. Browse current open roles and apply with context.',
      // Its own address, not /jobs. noindex alongside a canonical naming a
      // different page is a contradiction Google resolves by carrying the
      // noindex across, which pointed every dead job page at the one browse
      // page we most need indexed.
      path: this.requestedPath(),
      robots: 'noindex, follow',
    });
  }

  /**
   * The API did not answer.
   *
   * <p>Deliberately no noindex. At min-instances 0 a crawl that lands on a cold
   * instance fails every request, and the old code could not tell that apart
   * from a 404 -- so one cold start would have asked Google to drop fifteen
   * thousand live postings. This page will be right again on the next fetch.
   */
  private showUnavailable(): void {
    this.job = null;
    this.loading = false;
    this.unavailable = true;
    this.error = 'We could not load this role just now.';
    this.seo.setPage({
      title: 'Job Temporarily Unavailable | AIRRAL Jobs',
      description: 'This AIRRAL job could not be loaded just now. Try again in a moment, or browse the roles that are open.',
      path: this.requestedPath(),
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
