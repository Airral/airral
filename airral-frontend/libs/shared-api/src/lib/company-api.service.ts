import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CompanyProfile, UpdateCompanyProfileRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

/** The signed-in teammate's own company profile. */
@Injectable({
  providedIn: 'root'
})
export class CompanyApiService {
  constructor(private apiClient: ApiClientService) {}

  get(): Observable<CompanyProfile> {
    return this.apiClient.get<CompanyProfile>('/company');
  }

  update(request: UpdateCompanyProfileRequest): Observable<CompanyProfile> {
    return this.apiClient.put<CompanyProfile>('/company', request);
  }
}
