// libs/shared-types/src/lib/common.types.ts

export interface ApiResponse<T> {
  data: T;
  message: string;
  success: boolean;
}

export interface ErrorResponse {
  code: string;
  message: string;
}

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
}

export enum UserRole {
  ADMIN = 'ADMIN',                   // Full system access
  HR_MANAGER = 'HR_MANAGER',         // Full HR portal access
  MANAGER = 'MANAGER',               // Team management + employee features
  EMPLOYEE = 'EMPLOYEE',             // Self-service only
  APPLICANT = 'APPLICANT'            // Candidate portal access
}
