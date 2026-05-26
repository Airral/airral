import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { JobApiService } from '@airral/shared-api';
import { Job } from '@airral/shared-types';
import { catchError, of, timeout } from 'rxjs';

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
