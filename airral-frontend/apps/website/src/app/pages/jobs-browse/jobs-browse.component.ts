import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { HeaderComponent, FooterComponent } from '@airral/shared-ui';
import { CandidatePortalService, JobApiService } from '@airral/shared-api';
import { PORTAL_ROUTES } from '@airral/shared-utils';
import { WEBSITE_HEADER_LINKS, WEBSITE_HEADER_CTAS } from '../../shared/header-config';
import { PAGE_SEO } from '../../shared/seo-pages';
import { SeoService } from '../../shared/seo.service';
import {
  BrowseJob,
  dedupeBrowseJobs,
  fetchBrowseJobs,
  fetchMoreBrowseJobs,
  jobDetailLink,
  jobDetailPath,
  JobsBrowseData,
  JOBS_BROWSE_PAGE_SIZE,
} from '../../shared/job-route.resolvers';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';

/** Long enough that a normal typing speed sends one request, not eight. */
const SEARCH_DEBOUNCE_MS = 350;

@Component({
  selector: 'app-jobs-browse',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, HeaderComponent, FooterComponent],
  templateUrl: './jobs-browse.component.html',
  styleUrls: ['./jobs-browse.component.css'],
})
export class JobsBrowseComponent implements OnInit, OnDestroy {
  jobs: BrowseJob[] = [];
  filteredJobs: BrowseJob[] = [];
  searchQuery = '';
  activeDepartment = 'All';
  loading = false;
  loadingMore = false;
  hasMore = false;
  error: string | null = null;

  readonly headerLinks = WEBSITE_HEADER_LINKS;
  readonly headerCtas = WEBSITE_HEADER_CTAS;
  readonly applicantLoginUrl = `${PORTAL_ROUTES.APPLICANT}/login`;
  readonly applicantRegisterUrl = `${PORTAL_ROUTES.APPLICANT}/login?mode=register`;

  private nextOffset = JOBS_BROWSE_PAGE_SIZE;
  private readonly typed = new Subject<string>();
  private readonly subscriptions = new Subscription();
  private reloadRequest?: Subscription;
  private moreRequest?: Subscription;

  constructor(
    private jobApi: JobApiService,
    private candidateApi: CandidatePortalService,
    private route: ActivatedRoute,
    private router: Router,
    private seo: SeoService,
    private readonly changeDetectorRef: ChangeDetectorRef
  ) {}

  /**
   * The first list comes from the route resolver, already loaded.
   *
   * <p>The page used to fetch it here instead, which under SSR means the server
   * emits its HTML before the request comes back -- a crawler landing on the
   * page every job URL points at would have seen an empty roles section.
   * Everything below this only runs in response to something the visitor did,
   * so none of it happens during a server render.
   */
  ngOnInit() {
    this.subscriptions.add(
      this.route.queryParamMap.subscribe((params) => {
        this.searchQuery = params.get('q') || params.get('search') || '';
        this.applyFilters();
      })
    );

    this.subscriptions.add(
      this.route.data.subscribe((data) => {
        this.applyResult(data['jobs'] as JobsBrowseData | undefined);
      })
    );

    // Typing re-queries the feed rather than filtering what is on screen. This
    // page holds twenty-four rows of a fifteen-thousand-row catalogue, so a
    // client-side filter answered "nothing matches that yet" to nearly every
    // real search.
    this.subscriptions.add(
      this.typed
        .pipe(debounceTime(SEARCH_DEBOUNCE_MS), distinctUntilChanged())
        .subscribe((query) => this.reload(query))
    );
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.reloadRequest?.unsubscribe();
    this.moreRequest?.unsubscribe();
  }

  onSearchChange() {
    this.typed.next(this.searchQuery);
  }

  selectDepartment(dept: string) {
    this.activeDepartment = dept;
    this.applyFilters();
  }

  clearFilters() {
    this.activeDepartment = 'All';
    this.searchQuery = '';
    this.applyFilters();
    // Through the same subject the box uses, so a later retype of the query
    // just cleared is not swallowed as a duplicate.
    this.typed.next('');
  }

  loadMore() {
    if (this.loadingMore || !this.hasMore) {
      return;
    }

    this.loadingMore = true;
    this.moreRequest?.unsubscribe();
    this.moreRequest = fetchMoreBrowseJobs(this.candidateApi, {
      query: this.searchQuery.trim() || undefined,
      offset: this.nextOffset,
    }).subscribe((result) => {
      // Deduped against what is already on screen, not just within the page: an
      // employer-posted job reaches the feed as its own catalogue projection and
      // can surface on any page, next to the copy the first load already listed.
      this.jobs = dedupeBrowseJobs([...this.jobs, ...result.jobs]);
      // A failed page changes nothing: the button stays, and the offset stays
      // where it was so a retry asks for the page we missed rather than
      // restarting at zero and appending the rows already on screen.
      this.nextOffset = result.failed ? this.nextOffset : result.nextOffset;
      this.hasMore = result.failed ? this.hasMore : result.hasMore;
      this.loadingMore = false;
      this.applyFilters();
      this.repaint();
    });
  }

  /** Router commands for a role, so a synced posting does not link to /jobs/0. */
  detailLink(job: BrowseJob): (string | number)[] {
    return jobDetailLink(job);
  }

  /**
   * Where a synced posting is really applied to.
   *
   * <p>Employer-posted roles keep the portal link. A synced one has no internal
   * id to hand the portal -- the register CTA would carry jobId=0 on every one
   * of fifteen thousand cards -- and the application is completed on the
   * employer's own form anyway.
   */
  externalApply(job: BrowseJob): string | null {
    return job.id ? null : job.externalApplyUrl || null;
  }

  /**
   * The pay line, when there is one worth printing.
   *
   * <p>The feed fills salaryLabel with "Salary not listed" rather than leaving
   * it blank, and a card that says that about itself is noise on every row.
   *
   * <p>A label whose only digits are zeros is dropped too. The feed rounds to
   * thousands whenever the board stated no interval, so an amount that was
   * really a rate comes back as "USD $0k" -- which reads on a public card as an
   * employer saying the job pays nothing.
   */
  payLabel(job: BrowseJob): string | null {
    const label = (job.salaryLabel || '').trim();
    if (!label || /not listed/i.test(label)) {
      return null;
    }
    return /[0-9]/.test(label) && !/[1-9]/.test(label) ? null : label;
  }

  get departments(): string[] {
    const depts = new Set(this.jobs.map((j) => j.department).filter(Boolean) as string[]);
    return ['All', ...Array.from(depts)];
  }

  /** A search or a filter is hiding an otherwise stocked catalogue. */
  get noMatches(): boolean {
    return !this.loading && !this.error && this.filteredJobs.length === 0
      && (Boolean(this.searchQuery.trim()) || this.activeDepartment !== 'All');
  }

  /** Nothing is posted at all, which is a different thing to say. */
  get noRoles(): boolean {
    return !this.loading && !this.error && this.filteredJobs.length === 0 && !this.noMatches;
  }

  private applyFilters(): void {
    // Department only. The text side of this filter is gone now that the feed
    // answers the search: a list row carries no description to match against,
    // so re-filtering the feed's own results here threw most of them away.
    this.filteredJobs = this.activeDepartment === 'All'
      ? this.jobs
      : this.jobs.filter((job) => job.department === this.activeDepartment);
    this.updateJobsSeo();
  }

  private applyResult(result: JobsBrowseData | undefined): void {
    this.jobs = result?.jobs ?? [];
    this.hasMore = Boolean(result?.hasMore);
    this.nextOffset = result?.nextOffset ?? JOBS_BROWSE_PAGE_SIZE;
    this.error = result?.failed
      ? 'We could not load the roles just now. Try again in a moment.'
      : null;
    this.loading = false;
    this.applyFilters();
  }

  private reload(query: string): void {
    this.loading = true;
    this.error = null;
    this.repaint();
    this.reloadRequest?.unsubscribe();
    this.reloadRequest = fetchBrowseJobs(this.jobApi, this.candidateApi, {
      query: query.trim() || undefined,
    }).subscribe((result) => {
      this.applyResult(result);
      this.repaint();
    });

    // Keep the address bar on the search that produced the list, so it can be
    // shared and so the SearchAction target the site advertises -- /jobs?q= --
    // lands somewhere that resolves the same rows.
    void this.router.navigate([], {
      relativeTo: this.route,
      // search is the older alias this page still reads; dropping it keeps a
      // stale ?search= from overriding the box the visitor just typed into.
      queryParams: { q: query.trim() || null, search: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /**
   * These bundles ship without zone.js, so nothing schedules a render pass on
   * its own. A field assigned from inside an HTTP callback or a debounce timer
   * told change detection nothing: typing in the box changed the address bar
   * and left the resolver's first page on screen, and "Show more roles" sat on
   * "Loading…" for good once its page came back. Only the callbacks need this
   * -- the resolver's data is applied before the first render, and a template
   * event schedules its own pass.
   */
  private repaint(): void {
    this.changeDetectorRef.markForCheck();
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
              // Built from the same helper the cards link through. The job id
              // was enough while this list held only employer-posted roles; now
              // that the synced catalogue is in it, every one of those rows
              // carries id 0 and would be published here as /jobs/0.
              url: `https://www.airral.com${jobDetailPath(job)}`,
              name: job.title,
            })),
          },
        },
      ],
    });
  }
}
