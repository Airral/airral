import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CompanyFeedPostModel,
  FeedPageModel,
  FeedQueryModel,
  FeedReactionType
} from '@airral/shared-types';
import { ApiClientService } from './api-client.service';

@Injectable({
  providedIn: 'root'
})
export class FeedApiService {
  constructor(private apiClient: ApiClientService) {}

  getCommunityFeed(query: FeedQueryModel = {}): Observable<FeedPageModel> {
    return this.apiClient.get<FeedPageModel>(
      `/feed/community?page=${query.page ?? 1}&pageSize=${query.pageSize ?? 10}&sortBy=${query.sortBy ?? 'LATEST'}`
    );
  }

  reactToPost(postId: number, reaction: FeedReactionType): Observable<CompanyFeedPostModel> {
    return this.apiClient.post<CompanyFeedPostModel>(`/feed/community/${postId}/react`, { reaction });
  }

  getActivityByType(activityType: string): Observable<any[]> {
    return this.apiClient.get<any[]>(`/activity/type/${activityType}`);
  }

  getActivityByCategory(category: string): Observable<any[]> {
    return this.apiClient.get<any[]>(`/activity/category/${category}`);
  }

  getActivitySince(hours: number): Observable<any[]> {
    return this.apiClient.get<any[]>(`/activity/since?hours=${hours}`);
  }
}
