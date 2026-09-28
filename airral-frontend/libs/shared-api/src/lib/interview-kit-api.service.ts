import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { InterviewKit, InterviewKitRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

/** A company's interview kits: questions to ask and criteria to rate. */
@Injectable({
  providedIn: 'root'
})
export class InterviewKitApiService {
  constructor(private apiClient: ApiClientService) {}

  list(): Observable<InterviewKit[]> {
    return this.apiClient.get<InterviewKit[]>('/interview-kits');
  }

  create(request: InterviewKitRequest): Observable<InterviewKit> {
    return this.apiClient.post<InterviewKit>('/interview-kits', request);
  }

  update(id: number, request: InterviewKitRequest): Observable<InterviewKit> {
    return this.apiClient.put<InterviewKit>(`/interview-kits/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.apiClient.delete<void>(`/interview-kits/${id}`);
  }
}
