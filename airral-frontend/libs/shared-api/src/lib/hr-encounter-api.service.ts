// libs/shared-api/src/lib/hr-encounter-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HrEncounter, CreateEncounterRequest } from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class HrEncounterApiService {
  constructor(private apiClient: ApiClientService) {}

  createEncounter(request: CreateEncounterRequest): Observable<HrEncounter> {
    return this.apiClient.post<HrEncounter>('/encounters', request);
  }

  getAllEncounters(): Observable<HrEncounter[]> {
    return this.apiClient.get<HrEncounter[]>('/encounters');
  }

  getEncounterById(id: number): Observable<HrEncounter> {
    return this.apiClient.get<HrEncounter>(`/encounters/${id}`);
  }

  getEncountersByApplication(applicationId: number): Observable<HrEncounter[]> {
    return this.apiClient.get<HrEncounter[]>(`/encounters/application/${applicationId}`);
  }

  getEncountersByCandidate(candidateId: number): Observable<HrEncounter[]> {
    return this.apiClient.get<HrEncounter[]>(`/encounters/candidate/${candidateId}`);
  }

  updateEncounter(id: number, request: Partial<CreateEncounterRequest>): Observable<HrEncounter> {
    return this.apiClient.put<HrEncounter>(`/encounters/${id}`, request);
  }

  deleteEncounter(id: number): Observable<any> {
    return this.apiClient.delete<any>(`/encounters/${id}`);
  }
}
