import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface PublicStatistics {
  totalCompanies: number;
  totalJobs: number;
}

@Injectable({
  providedIn: 'root'
})
export class StatisticsService {
  private apiUrl = '/api/jobs/statistics/public';

  constructor(private http: HttpClient) {}

  getPublicStatistics(): Observable<PublicStatistics> {
    return this.http.get<PublicStatistics>(this.apiUrl);
  }
}
