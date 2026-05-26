import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CompanyFeedPostModel,
  CreateCommunityFeedPostRequest,
  FeedPageModel,
  FeedQueryModel,
  FeedReactionType,
  FeedSignalPageModel,
  NewsCategoryModel,
  NewsPageModel
} from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class FeedApiService {
  constructor(private apiClient: ApiClientService) {}

  /** GET /api/feed?page=1&size=10 */
  getPublicFeed(query: FeedQueryModel = {}): Observable<FeedPageModel> {
    const page = query.page ?? 1;
    const size = query.pageSize ?? 10;
    return this.apiClient.get<FeedPageModel>(`/feed?page=${page}&size=${size}`);
  }

  /** GET /api/feed/signals?size=12&q=software%20funding */
  getSignalFeed(query: { q?: string; size?: number } = {}): Observable<FeedSignalPageModel> {
    const params = new URLSearchParams();
    params.set('size', String(query.size ?? 12));
    if (query.q?.trim()) {
      params.set('q', query.q.trim());
    }
    return this.apiClient.get<FeedSignalPageModel>(`/feed/signals?${params.toString()}`);
  }

  /** GET /api/feed/news?category=TECH&size=30&q=optional */
  getNewsFeed(query: { category?: NewsCategoryModel | string; q?: string; size?: number } = {}): Observable<NewsPageModel> {
    const params = new URLSearchParams();
    params.set('category', query.category || 'TECH');
    params.set('size', String(query.size ?? 30));
    if (query.q?.trim()) {
      params.set('q', query.q.trim());
    }
    return this.apiClient.get<NewsPageModel>(`/feed/news?${params.toString()}`);
  }

  /** POST /api/feed/{postId}/react — toggle reaction */
  reactToPost(postId: number, reactionType: FeedReactionType): Observable<any> {
    return this.apiClient.post<any>(`/feed/${postId}/react`, { reactionType });
  }

  /** POST /api/feed/community — applicant-authored career/community post */
  createCommunityPost(request: CreateCommunityFeedPostRequest): Observable<CompanyFeedPostModel> {
    return this.apiClient.post<CompanyFeedPostModel>('/feed/community', request);
  }

  /** POST /api/feed/companies/{orgId}/follow — toggle follow */
  toggleFollowCompany(orgId: number): Observable<{ following: boolean }> {
    return this.apiClient.post<{ following: boolean }>(`/feed/companies/${orgId}/follow`, {});
  }
}
