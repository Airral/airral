// libs/shared-api/src/lib/contact-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

export interface ContactMessage {
  name: string;
  email: string;
  subject?: string;
  message: string;
}

/** The website's contact form. Messages go to the AIRRAL team's Slack channel. */
@Injectable({
  providedIn: 'root'
})
export class ContactApiService {
  constructor(private apiClient: ApiClientService) {}

  send(message: ContactMessage): Observable<{ message: string }> {
    return this.apiClient.post<{ message: string }>('/contact', message);
  }
}
