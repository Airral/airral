import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { NewsArticleModel, NewsCategoryModel } from '@airral/shared-types';

@Component({
  selector: 'app-news-briefing',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule],
  templateUrl: './news-briefing.component.html',
  styleUrls: ['./news-briefing.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class NewsBriefingComponent implements OnChanges {
  @Input() articles: NewsArticleModel[] = [];
  @Input() loading = false;
  @Input() error = '';
  @Input() categories: Array<{ value: NewsCategoryModel; label: string }> = [];
  @Input() activeCategory: NewsCategoryModel = 'TECH';
  @Output() refresh = new EventEmitter<void>();
  @Output() categoryChange = new EventEmitter<NewsCategoryModel>();
  @Output() openArticle = new EventEmitter<NewsArticleModel>();
  @Output() viewJobs = new EventEmitter<void>();
  currentArticleIndex = 0;
  private readonly savedArticleKeys = new Set<string>();
  private readonly hiddenArticleKeys = new Set<string>();
  private readonly followedSignals = new Set<string>();

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['activeCategory']) {
      this.currentArticleIndex = 0;
      this.hiddenArticleKeys.clear();
      return;
    }

    if (changes['articles']) {
      this.normalizeCurrentIndex();
    }
  }

  get activeCategoryLabel(): string {
    return this.categories.find((category) => category.value === this.activeCategory)?.label || 'Tech';
  }

  get leadArticle(): NewsArticleModel | null {
    return this.activeArticle;
  }

  get supportingArticles(): NewsArticleModel[] {
    return this.visibleArticles.slice(0, 9);
  }

  get hasArticles(): boolean {
    return this.visibleArticles.length > 0;
  }

  get visibleArticles(): NewsArticleModel[] {
    return this.articles.filter((article) => !this.hiddenArticleKeys.has(this.getArticleKey(article)));
  }

  get activeArticle(): NewsArticleModel | null {
    const articles = this.visibleArticles;
    if (!articles.length) {
      return null;
    }

    return articles[Math.min(this.currentArticleIndex, articles.length - 1)] ?? null;
  }

  get activePositionLabel(): string {
    return `${Math.min(this.currentArticleIndex + 1, this.visibleArticles.length)} of ${this.visibleArticles.length}`;
  }

  get savedCount(): number {
    return this.savedArticleKeys.size;
  }

  get hiddenCount(): number {
    return this.hiddenArticleKeys.size;
  }

  getSourceLabel(article: NewsArticleModel): string {
    return article.sourceName || article.sourceDomain || 'News source';
  }

  getSignalLabel(article: NewsArticleModel): string {
    return String(article.signalType || article.category || 'Company signal')
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/\b\w/g, (match) => match.toUpperCase());
  }

  getDateLabel(article: NewsArticleModel): string {
    if (!article.publishedAt) {
      return 'Recent';
    }

    const value = new Date(article.publishedAt).getTime();
    if (Number.isNaN(value)) {
      return 'Recent';
    }

    const minutes = Math.max(1, Math.floor((Date.now() - value) / 60000));
    if (minutes < 60) {
      return `${minutes}m`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
      return `${hours}h`;
    }
    const days = Math.floor(hours / 24);
    return `${days}d`;
  }

  getImpactLabel(article: NewsArticleModel): string {
    return article.whyItMatters || article.displayContext || 'Check the source before spending time on an application.';
  }

  getDecisionLabel(article: NewsArticleModel): string {
    const signal = String(article.signalType || article.category || '').toUpperCase();
    if (signal.includes('FUNDING')) {
      return 'Watch for new teams, hiring budgets, and fast-moving roles.';
    }
    if (signal.includes('HIRING')) {
      return 'Apply early before the role gets crowded.';
    }
    if (signal.includes('PRODUCT')) {
      return 'Look for product, support, sales, and operations openings tied to the launch.';
    }
    if (signal.includes('RISK')) {
      return 'Slow down and verify stability before investing time.';
    }
    if (signal.includes('ACQUISITION')) {
      return 'Check whether the team is growing, pausing, or changing ownership.';
    }
    return 'Use this as context before you spend time applying.';
  }

  getJobActionLabel(article: NewsArticleModel): string {
    const signal = String(article.signalType || '').toUpperCase();
    if (signal.includes('RISK')) {
      return 'Compare jobs';
    }
    if (signal.includes('FUNDING') || signal.includes('HIRING') || signal.includes('PRODUCT')) {
      return 'Find roles';
    }
    return 'View jobs';
  }

  getTrustLabel(article: NewsArticleModel): string {
    switch ((article.sourceTrustTier || '').toUpperCase()) {
      case 'HIGH':
        return 'Publisher';
      case 'LOW':
        return 'Verify';
      default:
        return 'Indexed';
    }
  }

  getScoreLabel(article: NewsArticleModel): string {
    return article.relevanceScore ? `${article.relevanceScore}%` : 'Signal';
  }

  getTags(article: NewsArticleModel): string[] {
    return (article.tags?.length ? article.tags : [article.category, article.signalType])
      .filter(Boolean)
      .slice(0, 3)
      .map((tag) => String(tag).replace(/_/g, ' ').toLowerCase());
  }

  getTopicLabel(article: NewsArticleModel): string {
    return this.getTags(article)[0] || this.getSignalLabel(article).toLowerCase();
  }

  selectArticle(index: number): void {
    this.currentArticleIndex = index;
  }

  previousArticle(event: Event): void {
    event.stopPropagation();
    const count = this.visibleArticles.length;
    this.currentArticleIndex = count ? (this.currentArticleIndex + count - 1) % count : 0;
  }

  nextArticle(event: Event): void {
    event.stopPropagation();
    const count = this.visibleArticles.length;
    this.currentArticleIndex = count ? (this.currentArticleIndex + 1) % count : 0;
  }

  toggleSaved(article: NewsArticleModel, event: Event): void {
    event.stopPropagation();
    const key = this.getArticleKey(article);
    if (this.savedArticleKeys.has(key)) {
      this.savedArticleKeys.delete(key);
      return;
    }
    this.savedArticleKeys.add(key);
  }

  hideArticle(article: NewsArticleModel, event: Event): void {
    event.stopPropagation();
    this.hiddenArticleKeys.add(this.getArticleKey(article));
    this.normalizeCurrentIndex();
  }

  showHiddenStories(): void {
    this.hiddenArticleKeys.clear();
    this.normalizeCurrentIndex();
  }

  toggleFollowSignal(article: NewsArticleModel, event: Event): void {
    event.stopPropagation();
    const signal = this.getSignalLabel(article);
    if (this.followedSignals.has(signal)) {
      this.followedSignals.delete(signal);
      return;
    }
    this.followedSignals.add(signal);
  }

  isArticleSaved(article: NewsArticleModel): boolean {
    return this.savedArticleKeys.has(this.getArticleKey(article));
  }

  isSignalFollowed(article: NewsArticleModel): boolean {
    return this.followedSignals.has(this.getSignalLabel(article));
  }

  trackArticle(_: number, article: NewsArticleModel): string {
    return this.getArticleKey(article);
  }

  private getArticleKey(article: NewsArticleModel): string {
    return article.id || article.canonicalUrl || article.sourceUrl || article.title;
  }

  private normalizeCurrentIndex(): void {
    const maxIndex = Math.max(0, this.visibleArticles.length - 1);
    this.currentArticleIndex = Math.min(this.currentArticleIndex, maxIndex);
  }
}
