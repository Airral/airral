// libs/shared-api/src/lib/department-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

export interface CreateDepartmentRequest {
  name: string;
  description?: string;
}

export interface Department {
  id: number;
  organizationId: number;
  name: string;
  description?: string;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

@Injectable({
  providedIn: 'root'
})
export class DepartmentApiService {
  constructor(private apiClient: ApiClientService) {}

  /**
   * Create a new department
   */
  createDepartment(request: CreateDepartmentRequest): Observable<Department> {
    return this.apiClient.post<Department>('/departments', request);
  }

  /**
   * Get all departments for the organization
   */
  getAllDepartments(): Observable<Department[]> {
    return this.apiClient.get<Department[]>('/departments');
  }

  /**
   * Get department by ID
   */
  getDepartmentById(id: number): Observable<Department> {
    return this.apiClient.get<Department>(`/departments/${id}`);
  }

  /**
   * Update department
   */
  updateDepartment(id: number, request: CreateDepartmentRequest): Observable<Department> {
    return this.apiClient.put<Department>(`/departments/${id}`, request);
  }

  /**
   * Delete department
   */
  deleteDepartment(id: number): Observable<any> {
    return this.apiClient.delete<any>(`/departments/${id}`);
  }
}
