// libs/shared-api/src/lib/job-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Job, JobStatus, CreateJobRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

interface OpenJobsParams {
  query?: string;
  department?: string;
}

@Injectable({
  providedIn: 'root'
})
export class JobApiService {
  constructor(private apiClient: ApiClientService) {}

  getAllJobs(): Observable<Job[]> {
    return this.apiClient.get<Job[]>('/jobs');
  }

  getOpenJobs(params: OpenJobsParams = {}): Observable<Job[]> {
    const searchParams = new URLSearchParams();
    const query = params.query?.trim();
    const department = params.department?.trim();

    if (query) {
      searchParams.set('q', query);
    }

    if (department && department !== 'All') {
      searchParams.set('department', department);
    }

    const queryString = searchParams.toString();
    return this.apiClient.get<Job[]>(`/jobs/open${queryString ? `?${queryString}` : ''}`);
  }

  getJobById(id: number): Observable<Job> {
    return this.apiClient.get<Job>(`/jobs/${id}`);
  }

  createJob(request: CreateJobRequest): Observable<Job> {
    return this.apiClient.post<Job>('/jobs', request);
  }

  updateJob(id: number, request: CreateJobRequest): Observable<Job> {
    return this.apiClient.put<Job>(`/jobs/${id}`, request);
  }

  deleteJob(id: number): Observable<any> {
    return this.apiClient.delete<any>(`/jobs/${id}`);
  }

  getJobsByStatus(status: string): Observable<Job[]> {
    return this.apiClient.get<Job[]>(`/jobs/status/${status}`);
  }
}
