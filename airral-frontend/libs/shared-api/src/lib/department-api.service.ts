// libs/shared-api/src/lib/department-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

export interface Department {
  id: number;
  organizationId: number;
  name: string;
  description?: string | null;
  isActive?: boolean;
}

export interface DepartmentRequest {
  name: string;
  description?: string;
}

/** A company's departments. Anyone in the company can list them; HR changes them. */
@Injectable({
  providedIn: 'root'
})
export class DepartmentApiService {
  constructor(private apiClient: ApiClientService) {}

  list(): Observable<Department[]> {
    return this.apiClient.get<Department[]>('/departments');
  }

  create(request: DepartmentRequest): Observable<Department> {
    return this.apiClient.post<Department>('/departments', request);
  }

  update(id: number, request: DepartmentRequest): Observable<Department> {
    return this.apiClient.put<Department>(`/departments/${id}`, request);
  }

  remove(id: number): Observable<void> {
    return this.apiClient.delete<void>(`/departments/${id}`);
  }
}
