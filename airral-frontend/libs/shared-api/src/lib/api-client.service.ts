// libs/shared-api/src/lib/api-client.service.ts
import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { API_BASE_URL } from '@airral/shared-utils';

/**
 * What every failure out of this client is. status is the HTTP status, or 0
 * when the request never reached a server.
 */
export interface ApiError extends Error {
  status: number;
}

@Injectable({
  providedIn: 'root'
})
export class ApiClientService {
  private baseUrl = API_BASE_URL;

  constructor(private http: HttpClient) {}

  get<T>(url: string): Observable<T> {
    return this.http.get<T>(`${this.baseUrl}${url}`).pipe(
      catchError(this.handleError)
    );
  }

  post<T>(url: string, body: any): Observable<T> {
    return this.http.post<T>(`${this.baseUrl}${url}`, body).pipe(
      catchError(this.handleError)
    );
  }

  put<T>(url: string, body: any): Observable<T> {
    return this.http.put<T>(`${this.baseUrl}${url}`, body).pipe(
      catchError(this.handleError)
    );
  }

  delete<T>(url: string): Observable<T> {
    return this.http.delete<T>(`${this.baseUrl}${url}`).pipe(
      catchError(this.handleError)
    );
  }

  /**
   * Every failure left here as a bare Error, which threw away the one thing a
   * caller sometimes has to have: the status.
   *
   * <p>The website renders job pages on the server now, and its route resolver
   * has to tell "this posting is gone" (404 from /api/jobs/{id}, 400 from the
   * external detail endpoint when the posting is no longer active) from "the
   * API did not answer" (500 while Cloud SQL is stopped overnight, 503 from the
   * degraded detail path, 429, a connection that never landed). It was
   * classifying everything it could not recognise as gone and attaching
   * noindex, so an API outage asked Google to drop live postings.
   *
   * <p>Carried as a property on the Error rather than as a new thrown type:
   * four apps already catch this and read only .message, and none of them
   * should have to change to keep working.
   */
  private handleError(error: HttpErrorResponse) {
    // error.error is null on an empty-bodied error response, and reading
    // .message straight off it threw a TypeError from inside catchError --
    // which reached the caller as a failure carrying no status at all, exactly
    // in the case where the status is what it needed.
    const body = error.error as { message?: string } | string | null | undefined;
    const bodyMessage = typeof body === 'string' ? undefined : body?.message;

    const failure = new Error(
      bodyMessage || error.message || 'An unknown error occurred'
    ) as ApiError;
    // 0 is what Angular reports when the request never got an answer at all
    // (DNS, TLS, connection refused), which is not a verdict about the resource.
    failure.status = typeof error.status === 'number' ? error.status : 0;

    return throwError(() => failure);
  }
}
