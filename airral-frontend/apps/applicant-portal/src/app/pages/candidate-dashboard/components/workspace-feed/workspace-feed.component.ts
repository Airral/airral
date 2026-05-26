import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { FeedPostType, FeedVisibility } from '@airral/shared-types';
import { CommunityPostDraft, WorkspacePost } from '../../models/candidate-dashboard.models';

type FeedLens = 'for_you' | 'following';
type FeedSort = 'quality' | 'new' | 'saved';

interface FeedTopic {
  label: string;
  count: number;
}

interface RoomSuggestion {
  room: string;
  posts: number;
  signals: number;
  latestMinutes: number;
}

@Component({
  selector: 'app-workspace-feed',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatButtonToggleModule, MatChipsModule, MatIconModule],
  templateUrl: './workspace-feed.component.html',
  styleUrls: ['./workspace-feed.component.css']
})
export class WorkspaceFeedComponent {
  @Input() posts: WorkspacePost[] = [];
  @Input() loading = false;
  @Input() hasMore = false;
  @Input() loadingMore = false;
  @Input() showComposer = true;
  @Input() profileInitial = 'A';
  @Input() searchFocus = 'Target roles';
  @Input() searchLocation = 'United States';
  @Input() workMode = 'Flexible';
  @Output() postAction = new EventEmitter<WorkspacePost>();
  @Output() askStarted = new EventEmitter<string>();
  @Output() postCreated = new EventEmitter<CommunityPostDraft>();
  @Output() loadMore = new EventEmitter<void>();

  activeLens: FeedLens = 'for_you';
  activeSort: FeedSort = 'quality';
  activeTopic = '';
  draftType: FeedPostType = 'JOB_SEARCH_ASK';
  draftVisibility: FeedVisibility = 'AUTHENTICATED';
  draftContent = '';
  private readonly savedPostKeys = new Set<string>();
  private readonly followedRoomKeys = new Set<string>();
  private readonly hiddenImagePostKeys = new Set<string>();
  private readonly expandedPostKeys = new Set<string>();
  private readonly bodyPreviewLength = 260;

  readonly composerTypes: Array<{ value: FeedPostType; label: string; icon: string; topic: string }> = [
    { value: 'JOB_SEARCH_ASK', label: 'Ask', icon: 'help', topic: 'Job search ask' },
    { value: 'CAREER_UPDATE', label: 'Update', icon: 'trending_up', topic: 'Career update' },
    { value: 'INTERVIEW_NOTE', label: 'Interview', icon: 'assignment', topic: 'Interview note' },
    { value: 'SALARY_INTEL', label: 'Salary', icon: 'payments', topic: 'Salary intel' },
    { value: 'REFERRAL_OFFER', label: 'Referral', icon: 'handshake', topic: 'Referral offer' },
  ];
  readonly audienceOptions: Array<{ value: FeedVisibility; label: string; description: string }> = [
    { value: 'AUTHENTICATED', label: 'Members', description: 'Visible to signed-in AIRRAL members.' },
    { value: 'APPLICANTS_ONLY', label: 'Applicants', description: 'Visible to applicant accounts only.' },
    { value: 'PUBLIC', label: 'Public', description: 'Visible outside your network. Avoid personal contact info.' },
  ];

  get avatarInitial(): string {
    return this.profileInitial?.trim().charAt(0).toUpperCase() || 'A';
  }

  get totalSavedCount(): number {
    const postSaves = this.posts.reduce((total, post) => total + post.saves, 0);
    return postSaves + this.savedPostKeys.size;
  }

  get followedCount(): number {
    return this.followedRoomKeys.size;
  }

  get topTopics(): FeedTopic[] {
    const counts = new Map<string, number>();
    this.posts.forEach((post) => {
      post.tags.slice(0, 4).forEach((tag) => counts.set(tag, (counts.get(tag) || 0) + 1));
    });

    return [...counts.entries()]
      .map(([label, count]) => ({ label, count }))
      .sort((first, second) => second.count - first.count || first.label.localeCompare(second.label))
      .slice(0, 6);
  }

  get signalHighlights(): WorkspacePost[] {
    return this.posts
      .filter((post) => post.signalType || post.sourceUrl)
      .sort((first, second) => second.depthScore - first.depthScore)
      .slice(0, 3);
  }

  get roomSuggestions(): RoomSuggestion[] {
    const rooms = new Map<string, RoomSuggestion>();
    this.posts.forEach((post) => {
      const key = post.room || post.author || 'AIRRAL';
      const current = rooms.get(key) || {
        room: key,
        posts: 0,
        signals: 0,
        latestMinutes: post.freshnessMinutes,
      };
      current.posts += 1;
      current.signals += post.signalType ? 1 : 0;
      current.latestMinutes = Math.min(current.latestMinutes, post.freshnessMinutes);
      rooms.set(key, current);
    });

    return [...rooms.values()]
      .sort((first, second) => second.signals - first.signals || first.latestMinutes - second.latestMinutes)
      .slice(0, 4);
  }

  setLens(lens: FeedLens): void {
    this.activeLens = lens;
  }

  setSort(sort: FeedSort): void {
    this.activeSort = sort;
  }

  setTopicFilter(topic: string): void {
    this.activeTopic = this.activeTopic === topic ? '' : topic;
  }

  get rankedPosts(): WorkspacePost[] {
    const lensPosts =
      this.activeLens === 'following'
        ? this.posts.filter((post) => post.lens === 'following')
        : [...this.posts];
    const scopedPosts = this.activeTopic
      ? lensPosts.filter((post) => post.tags.includes(this.activeTopic))
      : lensPosts;

    return [...scopedPosts].sort((first, second) => {
      if (this.activeSort === 'new') {
        return first.freshnessMinutes - second.freshnessMinutes;
      }
      if (this.activeSort === 'saved') {
        return second.saves - first.saves;
      }
      return second.depthScore - first.depthScore;
    });
  }

  get displayPosts(): WorkspacePost[] {
    return this.rankedPosts;
  }

  getRelativeTime(minutesOld: number): string {
    if (minutesOld < 60) return `${minutesOld}m ago`;
    const hours = Math.floor(minutesOld / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days}d ago`;
    const months = Math.floor(days / 30);
    if (months < 12) return `${months}mo ago`;
    const years = Math.floor(months / 12);
    return `${years}y ago`;
  }

  getSignalLabel(post: WorkspacePost): string {
    if (!post.signalType) {
      return 'Community';
    }
    const signalType = (post.signalType || post.postType || 'COMPANY_SIGNAL').toString();
    return signalType
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (match) => match.toUpperCase());
  }

  getConfidenceLabel(post: WorkspacePost): string {
    if (!post.signalType) {
      return 'Member post';
    }
    switch ((post.confidence || '').toUpperCase()) {
      case 'HIGH':
        return 'High confidence';
      case 'LOW':
        return 'Needs review';
      case 'MEDIUM':
        return 'Medium confidence';
      default:
        return 'Signal';
    }
  }

  getLinkedJobsLabel(post: WorkspacePost): string {
    if (!post.signalType) {
      const replies = post.replies ?? 0;
      return replies === 1 ? '1 reply' : `${replies} replies`;
    }
    const count = post.linkedJobsCount ?? post.replies ?? 0;
    if (count <= 0) {
      return 'Check matching jobs';
    }
    return count === 1 ? '1 linked job' : `${count} linked jobs`;
  }

  getSourceLabel(post: WorkspacePost): string {
    return post.sourceName || post.author || 'Source';
  }

  hasPostImage(post: WorkspacePost): boolean {
    return Boolean(post.imageUrl && !this.hiddenImagePostKeys.has(this.getPostKey(post)));
  }

  hidePostImage(post: WorkspacePost): void {
    this.hiddenImagePostKeys.add(this.getPostKey(post));
  }

  getImageAlt(post: WorkspacePost): string {
    return post.title ? `Article image for ${post.title}` : 'Article image';
  }

  openSource(post: WorkspacePost, event?: Event): void {
    event?.stopPropagation();
    if (post.sourceUrl) {
      window.open(post.sourceUrl, '_blank', 'noopener');
    }
  }

  trackPost(_: number, post: WorkspacePost): string {
    return this.getPostKey(post);
  }

  getFreshnessLabel(post: WorkspacePost): string {
    return `Published ${this.getRelativeTime(post.freshnessMinutes)}`;
  }

  getSavedLabel(post: WorkspacePost): string {
    const count = this.getSavedCount(post);
    return count === 1 ? '1 save' : `${count} saves`;
  }

  getContextLabel(post: WorkspacePost): string {
    if (post.action === 'View jobs') {
      return 'Use this to time your application';
    }
    if (post.action === 'Check context') {
      return 'Review before investing application time';
    }
    return 'Bring this into a room';
  }

  getPostSubtitle(post: WorkspacePost): string {
    const room = post.room && post.room !== post.author ? ` - ${post.room}` : '';
    return `${this.getSourceLabel(post)}${room}`;
  }

  getPostActionIcon(post: WorkspacePost): string {
    if (post.action === 'View jobs') {
      return 'work';
    }
    if (post.action === 'Check context') {
      return 'fact_check';
    }
    return 'forum';
  }

  getPostSourceIcon(post: WorkspacePost): string {
    return post.sourceUrl ? 'open_in_new' : 'article';
  }

  getSourceDisabled(post: WorkspacePost): boolean {
    return !post.sourceUrl;
  }

  getSourceAriaLabel(post: WorkspacePost): string {
    return post.sourceUrl ? `Open source from ${this.getSourceLabel(post)}` : 'Source unavailable';
  }

  getActionAriaLabel(post: WorkspacePost): string {
    return `${post.action} for ${post.room}`;
  }

  getLinkedJobsIcon(post: WorkspacePost): string {
    if (!post.signalType) {
      return 'forum';
    }
    return (post.linkedJobsCount ?? post.replies ?? 0) > 0 ? 'work' : 'search';
  }

  getSignalTone(post: WorkspacePost): string {
    if (!post.signalType) {
      return 'signal-pill community';
    }
    const signalType = (post.signalType || '').toUpperCase();
    if (signalType === 'RISK') {
      return 'signal-pill risk';
    }
    if (signalType === 'FUNDING' || signalType === 'HIRING') {
      return 'signal-pill high';
    }
    return 'signal-pill';
  }

  getConfidenceTone(post: WorkspacePost): string {
    const confidence = (post.confidence || '').toUpperCase();
    if (confidence === 'HIGH') {
      return 'confidence-pill high';
    }
    if (confidence === 'LOW') {
      return 'confidence-pill low';
    }
    return 'confidence-pill';
  }

  getPostMetaLabel(post: WorkspacePost): string {
    return `${this.getFreshnessLabel(post)} - ${this.getLinkedJobsLabel(post)}`;
  }

  getDisplayBody(post: WorkspacePost): string {
    if (this.isExpanded(post) || post.body.length <= this.bodyPreviewLength) {
      return post.body;
    }

    return `${post.body.slice(0, this.bodyPreviewLength).trim()}...`;
  }

  shouldShowReadMore(post: WorkspacePost): boolean {
    return post.body.length > this.bodyPreviewLength;
  }

  isExpanded(post: WorkspacePost): boolean {
    return this.expandedPostKeys.has(this.getPostKey(post));
  }

  toggleExpanded(post: WorkspacePost): void {
    this.toggleMembership(this.expandedPostKeys, this.getPostKey(post));
  }

  getSourceButtonText(post: WorkspacePost): string {
    return post.sourceUrl ? 'Source' : 'Source unavailable';
  }

  getSaveButtonTitle(post: WorkspacePost): string {
    return this.isSaved(post) ? 'Saved' : 'Save signal';
  }

  getFollowButtonTitle(post: WorkspacePost): string {
    return this.isFollowingRoom(post) ? 'Following company' : 'Follow company';
  }

  getQualityLabel(post: WorkspacePost): string {
    return post.signalType ? `${post.depthScore}% relevance` : `${post.depthScore}% useful`;
  }

  getSavedCount(post: WorkspacePost): number {
    return post.saves + (this.isSaved(post) ? 1 : 0);
  }

  isSaved(post: WorkspacePost): boolean {
    return this.savedPostKeys.has(this.getPostKey(post));
  }

  isFollowingRoom(post: WorkspacePost): boolean {
    return this.followedRoomKeys.has(post.room);
  }

  toggleSaved(post: WorkspacePost): void {
    this.toggleMembership(this.savedPostKeys, this.getPostKey(post));
  }

  toggleRoomFollow(post: WorkspacePost): void {
    this.toggleMembership(this.followedRoomKeys, post.room);
  }

  startAsk(topic: string): void {
    this.askStarted.emit(topic);
  }

  setDraftType(type: FeedPostType): void {
    this.draftType = type;
  }

  setDraftVisibility(visibility: FeedVisibility | string): void {
    this.draftVisibility = visibility as FeedVisibility;
  }

  primeComposer(type: FeedPostType): void {
    this.draftType = type;
  }

  getAudienceDescription(): string {
    return this.audienceOptions.find((option) => option.value === this.draftVisibility)?.description || '';
  }

  submitDraft(): void {
    const content = this.draftContent.trim();
    if (!content) {
      return;
    }

    const selectedType = this.composerTypes.find((type) => type.value === this.draftType);
    this.postCreated.emit({
      postType: this.draftType,
      visibility: this.draftVisibility,
      topic: selectedType?.topic || 'Community post',
      content,
      targetLabel: this.extractTargetLabel(content),
    });
    this.draftContent = '';
  }

  getComposerPlaceholder(): string {
    switch (this.draftType) {
      case 'CAREER_UPDATE':
        return 'Share a career change, milestone, or what kind of role you are open to...';
      case 'INTERVIEW_NOTE':
        return 'Share what helped in an interview loop, question pattern, or prep note...';
      case 'SALARY_INTEL':
        return 'Share salary range, location, level, or offer context that could help others...';
      case 'REFERRAL_OFFER':
        return 'Share where you can refer people and what roles fit best...';
      default:
        return 'Ask for resume feedback, company intel, interview prep, or help with a role...';
    }
  }

  getTypeIcon(post: WorkspacePost): string {
    return post.icon || this.composerTypes.find((type) => type.value === post.postType)?.icon || 'forum';
  }

  getRoomInitial(room: string): string {
    return room.trim().charAt(0).toUpperCase() || 'A';
  }

  getRoomMeta(room: RoomSuggestion): string {
    const posts = room.posts === 1 ? '1 post' : `${room.posts} posts`;
    const signals = room.signals === 1 ? '1 signal' : `${room.signals} signals`;
    return `${posts} · ${signals} · ${this.getRelativeTime(room.latestMinutes)}`;
  }

  getTopicLabel(topic: FeedTopic): string {
    return topic.count === 1 ? '1 post' : `${topic.count} posts`;
  }

  private toggleMembership(store: Set<string>, key: string): void {
    if (store.has(key)) {
      store.delete(key);
      return;
    }
    store.add(key);
  }

  private getPostKey(post: WorkspacePost): string {
    return `${post.author}-${post.title}`;
  }

  private extractTargetLabel(content: string): string | undefined {
    const companyMatch = content.match(/\b(at|with|for)\s+([A-Z][A-Za-z0-9&.\- ]{1,40})/);
    return companyMatch?.[2]?.trim();
  }
}
