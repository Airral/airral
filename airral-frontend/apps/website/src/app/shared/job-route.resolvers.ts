import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { ApiClientService, CandidatePortalService, JobApiService } from '@airral/shared-api';
import {
  CandidateJobDetail,
  CandidateJobPageResponse,
  CandidateJobSummary,
  Job,
} from '@airral/shared-types';
import { catchError, forkJoin, map, Observable, of, timeout, TimeoutError } from 'rxjs';

/**
 * Long enough to outlast a cold API.
 *
 * <p>Everything here resolves before the router renders, and under SSR that
 * means before a byte of HTML is written. The API runs at min-instances 0, so
 * the first request after a quiet period waits on a Spring Boot start --
 * measured at about twelve seconds, longer when the database has just woken.
 * At the old 2.5s every one of these fell into its catchError, and a crawler
 * arriving on a cold instance was served "This job is no longer available",
 * with noindex attached, on all fifteen thousand job URLs. A slow page beats a
 * page that tells Google to drop the listing.
 */
const JOB_ROUTE_TIMEOUT_MS = 20000;

/** How many synced postings the browse page asks for before the first paint. */
export const JOBS_BROWSE_PAGE_SIZE = 24;

/** What the catalogue calls a row that is really one of our own employer's jobs. */
const INTERNAL_SOURCE_TYPE = 'AIRRAL_INTERNAL';

/**
 * A browse row: a Job plus the few things only a synced posting carries.
 *
 * <p>The list endpoint hands over a pre-formatted salaryLabel rather than a
 * min/max pair, because it has no interval to format them against.
 */
export type BrowseJob = Job & {
  externalSource?: string;
  externalBoardToken?: string;
  externalJobId?: string;
  externalApplyUrl?: string;
  salaryLabel?: string;
  postedLabel?: string;
};

/** What /jobs needs for its first paint, all of it resolved before render. */
export interface JobsBrowseData {
  jobs: BrowseJob[];
  /** Whether the synced feed has pages behind the first one. */
  hasMore: boolean;
  nextOffset: number;
  /**
   * A feed did not answer and there is nothing to show, so the page can say
   * that rather than claim nothing is open.
   */
  failed: boolean;
}

/**
 * What a job route resolves to: the posting, or why there is not one.
 *
 * <p>"gone" and "could not load" used to be the same null, and the page
 * attached noindex to both -- so a single cold start or 502 during a crawl told
 * Google to drop a live posting. A page we failed to fetch is a page we should
 * say nothing about.
 */
export interface ResolvedJob {
  job: Job | null;
  /** The API did not answer, as opposed to answering that the posting is gone. */
  unavailable: boolean;
}

const JOB_GONE: ResolvedJob = { job: null, unavailable: false };
const JOB_UNAVAILABLE: ResolvedJob = { job: null, unavailable: true };

const NO_BROWSE_JOBS: JobsBrowseData = { jobs: [], hasMore: false, nextOffset: 0, failed: true };

/**
 * The public browse list, from both places a job can live.
 *
 * <p>/api/jobs/open answers honestly and answers with nothing: the
 * employer-posted table has no OPEN rows, while the fifteen thousand synced
 * postings live in external_job_postings and are reachable only through the
 * candidate feed. So the marketing site's one browse surface -- the page the
 * whole sitemap funnels into -- rendered an empty state. Both feeds are read
 * and concatenated, employer-posted first because those are ours, so neither
 * disappears when the other is empty, then deduplicated because the two feeds
 * overlap on exactly those employer-posted rows.
 *
 * <p>Exported rather than inlined into the resolver because the in-page search
 * runs the same query again, and a browse list that disagreed with the one the
 * route resolved would be worse than either.
 */
export function fetchBrowseJobs(
  jobApi: JobApiService,
  candidateApi: CandidatePortalService,
  options: { query?: string; department?: string } = {}
): Observable<JobsBrowseData> {
  // Each branch swallows its own failure before forkJoin sees it. forkJoin
  // fails as a unit, and one slow feed must not blank the page the other
  // could have filled.
  const employerPosted = jobApi
    .getOpenJobs({ query: options.query, department: options.department })
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      catchError(() => of(null))
    );

  const synced = fetchSyncedPage(candidateApi, options.query, 0);

  return forkJoin([employerPosted, synced]).pipe(
    map(([posted, page]): JobsBrowseData => {
      const jobs = dedupeBrowseJobs([
        ...(posted ?? []),
        ...(page?.jobs ?? []).map(summaryToBrowseJob),
      ]);

      return {
        jobs,
        hasMore: Boolean(page?.hasMore),
        nextOffset: page?.nextOffset ?? JOBS_BROWSE_PAGE_SIZE,
        // Both feeds failing is not the only way to end up with nothing to
        // show. /api/jobs/open answers [] today, so a candidate feed that did
        // not come back left the page confidently announcing that no roles are
        // open, which is a claim about the catalogue we had not managed to read.
        failed: (posted === null || page === null) && jobs.length === 0,
      };
    }),
    catchError(() => of(NO_BROWSE_JOBS))
  );
}

/**
 * The next page of synced postings.
 *
 * <p>Only the synced feed: the employer-posted list is not paged and is already
 * on screen, so asking for it again would duplicate every row.
 */
export function fetchMoreBrowseJobs(
  candidateApi: CandidatePortalService,
  options: { query?: string; offset: number }
): Observable<JobsBrowseData> {
  return fetchSyncedPage(candidateApi, options.query, options.offset).pipe(
    map((page): JobsBrowseData => (page
      ? {
          jobs: page.jobs.map(summaryToBrowseJob),
          hasMore: Boolean(page.hasMore),
          nextOffset: page.nextOffset ?? options.offset + JOBS_BROWSE_PAGE_SIZE,
          failed: false,
        }
      : NO_BROWSE_JOBS)),
    catchError(() => of(NO_BROWSE_JOBS))
  );
}

/** One page of the candidate feed, or null when it did not answer. */
function fetchSyncedPage(
  candidateApi: CandidatePortalService,
  query: string | undefined,
  offset: number
): Observable<CandidateJobPageResponse | null> {
  return candidateApi
    .getRecommendedJobsPage(JOBS_BROWSE_PAGE_SIZE, offset, undefined, query)
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      catchError(() => of(null))
    );
}

export const openJobsResolver: ResolveFn<JobsBrowseData> = (route) =>
  fetchBrowseJobs(inject(JobApiService), inject(CandidatePortalService), {
    query: route.queryParamMap.get('q') || route.queryParamMap.get('search') || undefined,
    department: route.queryParamMap.get('department') || undefined,
  });

export const jobDetailResolver: ResolveFn<ResolvedJob> = (route) => {
  const id = Number(route.paramMap.get('id'));
  if (!Number.isFinite(id)) {
    return of(JOB_GONE);
  }

  return inject(JobApiService)
    .getJobById(id)
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      map((job): ResolvedJob => (job ? { job, unavailable: false } : JOB_GONE)),
      catchError((error) => of(toFailure(error)))
    );
};

/**
 * A synced posting, rendered on the public site.
 *
 * <p>These are the catalogue -- more than eleven thousand of them against zero
 * employer-posted jobs -- and until now none had an address. Nothing linked to
 * one, nothing could be shared, the sitemap was an empty urlset, and the MCP
 * tools could only send someone to the employer's own form because there was no
 * AIRRAL page to send them to instead.
 *
 * <p>Three path segments, mirroring the detail endpoint. They arrive
 * percent-decoded by the router, which is what the API expects: Workday
 * identifiers contain encoded slashes, so the sitemap writes them
 * double-encoded and one layer comes off here.
 */
export const externalJobDetailResolver: ResolveFn<ResolvedJob> = (route) => {
  const source = route.paramMap.get('source');
  const board = route.paramMap.get('board');
  const externalId = route.paramMap.get('externalId');

  if (!source || !board || !externalId) {
    return of(JOB_GONE);
  }

  const path = `/candidate/jobs/source/${encodeURIComponent(source)}`
    + `/${encodeURIComponent(board)}/${encodeURIComponent(externalId)}`;

  return inject(ApiClientService)
    .get<CandidateJobDetail>(path)
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      map((detail): ResolvedJob => (detail ? { job: toJob(detail), unavailable: false } : JOB_GONE)),
      catchError((error) => of(toFailure(error)))
    );
};

/**
 * Router commands for a job, left unencoded -- the router escapes each segment
 * itself, and pre-encoding here would double-encode the %2F inside a Workday
 * identifier.
 */
export function jobDetailLink(job: Job): (string | number)[] {
  const j = job as BrowseJob;

  if (j.externalSource && j.externalBoardToken && j.externalJobId) {
    return ['/jobs', j.externalSource, j.externalBoardToken, j.externalJobId];
  }

  return ['/jobs', job.id];
}

/**
 * The same address as a string, for canonical links and structured data.
 *
 * <p>A synced posting has no internal job id -- it is addressed by its source,
 * board and external id -- so building the URL from job.id would point every one
 * of them at /jobs/0. A canonical that names the wrong page is worse than none:
 * it tells a search engine that eleven thousand postings are all the same
 * document. Kept next to jobDetailLink so the link a visitor follows and the URL
 * we publish for it cannot drift apart.
 */
export function jobDetailPath(job: Job): string {
  const [, ...segments] = jobDetailLink(job);
  return `/jobs/${segments.map((segment) => encodeURIComponent(String(segment))).join('/')}`;
}

/**
 * The only statuses that are an answer about the posting rather than about the
 * API's health.
 *
 * <p>404 is /api/jobs/{id} on an employer-posted job that is not open. 400 is
 * the external detail endpoint refusing a posting existsActiveJob no longer
 * finds ("Job detail is only available for active AIRRAL postings"), which is
 * this API's way of saying gone. 410 is not emitted today and is here because
 * it means exactly this and would otherwise be read as an outage.
 */
const GONE_STATUSES = new Set([400, 404, 410]);

/**
 * An unanswered request, told apart from a genuine 404.
 *
 * <p>This used to recognise only a timeout, and called everything else gone --
 * which meant a page that said noindex. Under SSR that is the whole catalogue's
 * indexing riding on the API being up at the moment a crawler arrives, and it
 * is not: gcp-cloudsql-power.yml stops Cloud SQL 20:00-08:00 ET every night, so
 * for half of every day a job-detail read is a RuntimeException that
 * GlobalExceptionHandler turns into a 500. The degraded detail path answers 503
 * and says "try again shortly" in as many words. Neither is a TimeoutError, and
 * both used to end as "noindex" on a live posting.
 *
 * <p>So the default is inverted: unavailable unless the API actually said the
 * posting is gone. The cost of the new default is a dead posting that keeps
 * rendering "no longer available" without noindex until a crawl catches a real
 * 400 or 404 -- Google treats that page as a soft 404 anyway. The cost of the
 * old one was deindexing eleven thousand live postings because the database was
 * asleep.
 */
function toFailure(error: unknown): ResolvedJob {
  if (error instanceof TimeoutError) {
    return JOB_UNAVAILABLE;
  }

  // 0 is a request that never reached the API at all, and a non-HTTP throw
  // (ApiClientService failing inside its own error handler, say) has no status
  // -- neither is the API telling us anything about this posting.
  const status = (error as { status?: unknown } | null | undefined)?.status;

  return typeof status === 'number' && GONE_STATUSES.has(status)
    ? JOB_GONE
    : JOB_UNAVAILABLE;
}

/**
 * Maps a synced posting onto the shape this page already renders.
 *
 * <p>id is 0 because a synced posting has no internal job id -- nothing on the
 * page reads it, and the canonical URL is built from the source triple instead.
 */
function toJob(detail: CandidateJobDetail): Job {
  return {
    id: 0,
    organizationName: detail.companyName,
    organizationDomain: detail.companyDomain,
    organizationLogoUrl: detail.companyLogoUrl,
    title: detail.title,
    description: detail.descriptionText || detail.descriptionExcerpt || '',
    department: detail.department,
    location: detail.location,
    employmentType: detail.employmentType,
    salaryMin: detail.salaryMin,
    salaryMax: detail.salaryMax,
    salaryCurrency: detail.salaryCurrency,
    salaryPeriod: detail.salaryPeriod,
    status: 'OPEN',
    createdAt: detail.sourceUpdatedAt || '',
    updatedAt: detail.sourceUpdatedAt || '',
    // Carried through untouched for schema.org validThrough. The candidate DTO
    // does not serialise external_job_postings.expires_at yet, so this is null
    // today and starts working the day the API sends it -- rather than the page
    // inventing an expiry, which would drop a live posting out of results early.
    expiresAt: (detail as CandidateJobDetail & { expiresAt?: string }).expiresAt,
    externalApplyUrl: detail.applyUrl || detail.jobUrl,
    externalSource: detail.sourceType,
    externalBoardToken: detail.sourceBoardToken,
    externalJobId: detail.externalJobId,
  } as Job;
}

/**
 * The same mapping for a list row.
 *
 * <p>description is empty because the list endpoint deliberately does not
 * serialise the posting body -- a hundred rows of prose the card would only
 * truncate -- so the card falls back to the meta line.
 */
function summaryToBrowseJob(summary: CandidateJobSummary): BrowseJob {
  const internalId = internalJobId(summary);

  return {
    id: internalId ?? 0,
    organizationName: summary.companyName,
    organizationDomain: summary.companyDomain,
    organizationLogoUrl: summary.companyLogoUrl,
    title: summary.title,
    description: '',
    department: summary.department,
    location: summary.location,
    employmentType: summary.employmentType,
    status: 'OPEN',
    createdAt: summary.sourceUpdatedAt || '',
    updatedAt: summary.sourceUpdatedAt || '',
    // Left off a projected row on purpose: it is one of our own jobs, addressed
    // by its internal id and applied to in the portal, not on a board.
    externalApplyUrl: internalId ? undefined : summary.applyUrl || summary.jobUrl,
    externalSource: internalId ? undefined : summary.sourceType,
    externalBoardToken: internalId ? undefined : summary.sourceBoardToken,
    externalJobId: internalId ? undefined : summary.externalJobId,
    salaryLabel: summary.salaryLabel,
    postedLabel: summary.postedLabel,
  } as BrowseJob;
}

/**
 * The internal job id behind a catalogue row, when the row is one of ours.
 *
 * <p>Every OPEN employer-posted job is also written into external_job_postings
 * by InternalJobCatalogProjectionService, under source AIRRAL_INTERNAL with the
 * job's own id as its external id, and the candidate feed returns those rows
 * alongside the boards' -- exempted from the age cutoff, so always. Merging the
 * two feeds therefore lists each of our own jobs twice unless the projected copy
 * is recognised for what it is.
 */
function internalJobId(summary: CandidateJobSummary): number | null {
  if (summary.sourceType !== INTERNAL_SOURCE_TYPE) {
    return null;
  }

  const id = Number(summary.externalJobId);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/**
 * One row per posting.
 *
 * <p>Only an employer-posted job can arrive twice -- once from /api/jobs/open
 * and once as its catalogue projection -- so the key is the internal id, and a
 * board's row (id 0, addressed by its source triple) is never collapsed against
 * another. Two copies of one job would otherwise appear as two cards and, worse,
 * as two URLs for the same posting in the page's ItemList.
 */
export function dedupeBrowseJobs(jobs: BrowseJob[]): BrowseJob[] {
  const seen = new Set<number>();

  return jobs.filter((job) => {
    if (!job.id) {
      return true;
    }
    if (seen.has(job.id)) {
      return false;
    }
    seen.add(job.id);
    return true;
  });
}
