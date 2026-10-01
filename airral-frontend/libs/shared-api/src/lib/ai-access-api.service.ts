// libs/shared-api/src/lib/ai-access-api.service.ts
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClientService } from './api-client.service';

/** One of your keys, as it may be shown after it was made: never the secret. */
export interface AiAccessKey {
  keyId: string;
  name: string;
  prefix: string;
  scopes: string[];
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  expired: boolean;
}

/**
 * Whether connecting an AI assistant is on for this account, and its keys.
 * `included` false means the paid feature is not on the account: link the page
 * only if keys remain, so they can be revoked.
 */
export interface AiAccessOverview {
  included: boolean;
  available: boolean;
  reason: string | null;
  message: string | null;
  mcpUrl: string;
  maxKeys: number;
  keyLifetimeDays: number;
  /** What a key made now would hold, for saying what the assistant can do. */
  selfServiceScopes: string[];
  /** Always your keys that still exist, even when the feature is off, so you can revoke them. */
  keys: AiAccessKey[];
}

/** A key just made. `key` is the only copy there will ever be. */
export interface IssuedAiAccessKey {
  key: string;
  keyId: string;
  name: string;
  scopes: string[];
  ratePerMinute: number;
  expiresAt: string | null;
  mcpUrl: string;
}

/** Your own keys for connecting an AI assistant (Claude and others) to AIRRAL. */
@Injectable({
  providedIn: 'root'
})
export class AiAccessApiService {
  constructor(private apiClient: ApiClientService) {}

  overview(): Observable<AiAccessOverview> {
    return this.apiClient.get<AiAccessOverview>('/account/api-keys');
  }

  create(name: string): Observable<IssuedAiAccessKey> {
    return this.apiClient.post<IssuedAiAccessKey>('/account/api-keys', { name });
  }

  revoke(keyId: string): Observable<{ keyId: string; revoked: boolean }> {
    return this.apiClient.delete<{ keyId: string; revoked: boolean }>(`/account/api-keys/${encodeURIComponent(keyId)}`);
  }
}
