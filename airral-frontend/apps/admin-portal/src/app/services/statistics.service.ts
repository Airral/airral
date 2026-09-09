import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from '@airral/shared-api';

export interface PublicStatistics {
  totalCompanies: number;
  totalJobs: number;
}

@Injectable({
  providedIn: 'root'
})
export class StatisticsService {
  private readonly api = inject(ApiClientService);

  /**
   * Goes through ApiClientService rather than HttpClient directly, so the URL
   * is built from API_BASE_URL, same as the API key screen.
   *
   * This used to request a relative '/api/jobs/statistics/public', which
   * resolved to the admin portal's own static host. That host answers unknown
   * paths with index.html, so the call came back HTTP 200 with an HTML body and
   * failed only when Angular tried to parse it as JSON -- an ok:false on a 200,
   * which is why the page looked broken rather than unreachable.
   *
   * The path omits the leading '/api' because API_BASE_URL already ends with it.
   */
  getPublicStatistics(): Observable<PublicStatistics> {
    return this.api.get<PublicStatistics>('/jobs/statistics/public');
  }
}
