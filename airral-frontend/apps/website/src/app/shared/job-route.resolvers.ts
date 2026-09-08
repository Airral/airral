import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { ApiClientService, JobApiService } from '@airral/shared-api';
import { CandidateJobDetail, Job } from '@airral/shared-types';
import { catchError, map, of, timeout } from 'rxjs';

const JOB_ROUTE_TIMEOUT_MS = 2500;

export const openJobsResolver: ResolveFn<Job[]> = (route) =>
  inject(JobApiService)
    .getOpenJobs({
      query: route.queryParamMap.get('q') || route.queryParamMap.get('search') || undefined,
      department: route.queryParamMap.get('department') || undefined,
    })
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      catchError(() => of([]))
    );

export const jobDetailResolver: ResolveFn<Job | null> = (route) => {
  const id = Number(route.paramMap.get('id'));
  if (!Number.isFinite(id)) {
    return of(null);
  }

  return inject(JobApiService)
    .getJobById(id)
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      catchError(() => of(null))
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
export const externalJobDetailResolver: ResolveFn<Job | null> = (route) => {
  const source = route.paramMap.get('source');
  const board = route.paramMap.get('board');
  const externalId = route.paramMap.get('externalId');

  if (!source || !board || !externalId) {
    return of(null);
  }

  const path = `/candidate/jobs/source/${encodeURIComponent(source)}`
    + `/${encodeURIComponent(board)}/${encodeURIComponent(externalId)}`;

  return inject(ApiClientService)
    .get<CandidateJobDetail>(path)
    .pipe(
      timeout(JOB_ROUTE_TIMEOUT_MS),
      map((detail) => (detail ? toJob(detail) : null)),
      catchError(() => of(null))
    );
};

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
    status: 'OPEN',
    createdAt: detail.sourceUpdatedAt || '',
    updatedAt: detail.sourceUpdatedAt || '',
    externalApplyUrl: detail.applyUrl || detail.jobUrl,
    externalSource: detail.sourceType,
    externalBoardToken: detail.sourceBoardToken,
    externalJobId: detail.externalJobId,
  } as Job;
}
