import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from '@airral/shared-api';

/** A key as it can be shown after issuance: identity, never the secret. */
export interface ApiKeySummary {
  keyId: string;
  name: string;
  prefix: string;
  role: string;
  scopes: string[];
  lastUsedAt: string | null;
  expiresAt: string | null;
  createdAt: string;
  active: boolean;
  revokedAt: string | null;
}

/**
 * A freshly issued key. `key` is the only copy that will ever exist — the
 * server stored a hash — so it is shown once and then gone.
 */
export interface IssuedApiKey {
  key: string;
  keyId: string;
  name: string;
  role: string;
  scopes: string[];
  environment: string;
  ratePerMinute: number;
  expiresAt: string | null;
  warning: string;
  connect: string;
}

export interface IssueApiKeyRequest {
  email: string;
  name: string;
  expiresInDays?: number;
  ratePerMinute?: number;
}

@Injectable({ providedIn: 'root' })
export class ApiKeyService {
  private readonly api = inject(ApiClientService);

  /**
   * Goes through ApiClientService rather than HttpClient directly, so the URL
   * is built from API_BASE_URL. The admin portal is served from its own origin,
   * so a relative '/api/...' would resolve to admin.airral.com and 404.
   *
   * Paths here omit the leading '/api' because API_BASE_URL already ends with
   * it -- including it produced /api/api/admin/api-keys and a 500.
   */
  issue(request: IssueApiKeyRequest): Observable<IssuedApiKey> {
    return this.api.post<IssuedApiKey>('/admin/api-keys', request);
  }

  listFor(email: string): Observable<ApiKeySummary[]> {
    return this.api.get<ApiKeySummary[]>(
      `/admin/api-keys?email=${encodeURIComponent(email)}`
    );
  }

  revoke(keyId: string, reason: string): Observable<unknown> {
    return this.api.delete(
      `/admin/api-keys/${encodeURIComponent(keyId)}?reason=${encodeURIComponent(reason)}`
    );
  }
}
