import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { CandidatePortalService } from '@airral/shared-api';
import { CandidateJobDetail } from '@airral/shared-types';
import { catchError, of, Subject, takeUntil, timeout } from 'rxjs';
import { RecommendedRole } from '../../models/candidate-dashboard.models';

interface JobFact {
  icon: string;
  label: string;
  value: string;
}

interface JobDescriptionSection {
  icon: string;
  title: string;
  body: string;
}

@Component({
  selector: 'app-recommended-jobs',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatChipsModule, MatIconModule, MatMenuModule],
  templateUrl: './recommended-jobs.component.html',
  styleUrls: ['./recommended-jobs.component.css']
})
export class RecommendedJobsComponent implements OnChanges, OnDestroy {
  @Input() roles: RecommendedRole[] = [];
  @Input() hasMoreFromServer = false;
  @Input() loadingMore = false;
  @Output() applyRole = new EventEmitter<RecommendedRole>();
  @Output() askRoom = new EventEmitter<RecommendedRole>();
  @Output() loadMoreRoles = new EventEmitter<void>();

  private readonly savedRoleKeys = new Set<string>();
  private readonly detailCache = new Map<string, CandidateJobDetail>();
  private readonly destroy$ = new Subject<void>();
  private activeDetailRequest = 0;

  selectedRole: RecommendedRole | null = null;
  selectedDetail: CandidateJobDetail | null = null;
  detailLoading = false;
  detailError = false;
  descriptionExpanded = false;
  easyApplyOnly = false;
  remoteOnly = false;
  sourceFilter = 'ALL';
  dateFilter: 'ALL' | '1' | '7' | '15' | '30' = 'ALL';
  salaryListedOnly = false;
  visibleRoleCount = 10;

  private readonly rolePageSize = 10;

  readonly dateFilterOptions: Array<{ value: 'ALL' | '1' | '7' | '15' | '30'; label: string }> = [
    { value: 'ALL', label: 'Any time' },
    { value: '1', label: 'Past 24 hours' },
    { value: '7', label: 'Past 7 days' },
    { value: '15', label: 'Past 15 days' },
    { value: '30', label: 'Past 30 days' },
  ];

  constructor(
    private candidatePortalService: CandidatePortalService,
    private changeDetectorRef: ChangeDetectorRef
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (!changes['roles']) {
      return;
    }

    if (this.selectedRole) {
      const selectedKey = this.getRoleKey(this.selectedRole);
      const selectedRoleStillExists = this.roles.some((role) => this.getRoleKey(role) === selectedKey);
      if (!selectedRoleStillExists) {
        this.selectedRole = null;
      }
    }

    this.refreshSelectionAfterFilter();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  get selectedJob(): RecommendedRole | null {
    const filteredRoles = this.filteredRoles;
    if (this.selectedRole && filteredRoles.some((role) => this.getRoleKey(role) === this.getRoleKey(this.selectedRole!))) {
      return this.selectedRole;
    }

    return filteredRoles[0] ?? null;
  }

  get filteredRoles(): RecommendedRole[] {
    return this.roles.filter((role) => {
      if (this.easyApplyOnly && !role.easyApply) {
        return false;
      }
      if (this.remoteOnly && !this.isRemoteRole(role)) {
        return false;
      }
      if (this.sourceFilter !== 'ALL' && (role.sourceType || role.sourceName || '').toUpperCase() !== this.sourceFilter) {
        return false;
      }
      if (this.dateFilter !== 'ALL' && this.getPostedAgeDays(role) > Number(this.dateFilter)) {
        return false;
      }
      if (this.salaryListedOnly && this.isSalaryMissing(role)) {
        return false;
      }

      return true;
    });
  }

  get visibleRoles(): RecommendedRole[] {
    return this.filteredRoles.slice(0, this.visibleRoleCount);
  }

  get visibleRoleTotal(): number {
    return Math.min(this.visibleRoleCount, this.filteredRoles.length);
  }

  get hasMoreRoles(): boolean {
    return this.visibleRoleCount < this.filteredRoles.length || this.hasMoreFromServer;
  }

  get activeFilterCount(): number {
    return [
      this.easyApplyOnly,
      this.remoteOnly,
      this.sourceFilter !== 'ALL',
      this.dateFilter !== 'ALL',
      this.salaryListedOnly,
    ].filter(Boolean).length;
  }

  getInitials(company: string): string {
    return company
      .split(' ')
      .map((word) => word.charAt(0))
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }

  handleLogoError(role: RecommendedRole): void {
    role.companyLogoUrl = undefined;
  }

  toggleSaved(role: RecommendedRole): void {
    const key = this.getRoleKey(role);
    if (this.savedRoleKeys.has(key)) {
      this.savedRoleKeys.delete(key);
      return;
    }
    this.savedRoleKeys.add(key);
  }

  isSaved(role: RecommendedRole): boolean {
    return this.savedRoleKeys.has(this.getRoleKey(role));
  }

  isSelected(role: RecommendedRole): boolean {
    return this.selectedJob ? this.getRoleKey(this.selectedJob) === this.getRoleKey(role) : false;
  }

  selectRole(role: RecommendedRole): void {
    this.selectedRole = role;
    this.descriptionExpanded = false;
    this.loadDetailForRole(role);
  }

  trackByRole = (_index: number, role: RecommendedRole): string => this.getRoleKey(role);

  toggleEasyApply(): void {
    this.easyApplyOnly = !this.easyApplyOnly;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  toggleRemote(): void {
    this.remoteOnly = !this.remoteOnly;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  setSourceFilter(source: string): void {
    this.sourceFilter = source;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  setDateFilter(filter: 'ALL' | '1' | '7' | '15' | '30'): void {
    this.dateFilter = filter;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  toggleSalaryListed(): void {
    this.salaryListedOnly = !this.salaryListedOnly;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  clearFilters(): void {
    this.easyApplyOnly = false;
    this.remoteOnly = false;
    this.sourceFilter = 'ALL';
    this.dateFilter = 'ALL';
    this.salaryListedOnly = false;
    this.resetVisibleRoles();
    this.refreshSelectionAfterFilter();
  }

  showMoreRoles(): void {
    if (this.visibleRoleCount < this.filteredRoles.length) {
      this.visibleRoleCount += this.rolePageSize;
      return;
    }

    if (this.hasMoreFromServer) {
      this.visibleRoleCount += this.rolePageSize;
      this.loadMoreRoles.emit();
    }
  }

  getSourceFilterLabel(): string {
    if (this.sourceFilter === 'ALL') {
      return 'Source';
    }

    return this.sourceLabel(this.sourceFilter);
  }

  getDateFilterLabel(): string {
    return this.dateFilterOptions.find((option) => option.value === this.dateFilter)?.label ?? 'Date posted';
  }

  getSourceOptions(): Array<{ value: string; label: string; count: number }> {
    const sourceCounts = this.roles.reduce((counts, role) => {
      const source = (role.sourceType || role.sourceName || '').toUpperCase();
      if (!source) {
        return counts;
      }

      counts.set(source, (counts.get(source) ?? 0) + 1);
      return counts;
    }, new Map<string, number>());

    return [
      { value: 'ALL', label: 'All sources', count: this.roles.length },
      ...Array.from(sourceCounts.entries())
        .sort(([first], [second]) => first.localeCompare(second))
        .map(([value, count]) => ({ value, label: this.sourceLabel(value), count })),
    ];
  }

  getDateOptionCount(value: 'ALL' | '1' | '7' | '15' | '30'): number {
    if (value === 'ALL') {
      return this.roles.length;
    }

    return this.roles.filter((role) => this.getPostedAgeDays(role) <= Number(value)).length;
  }

  getDetailSalary(role: RecommendedRole): string {
    return this.selectedDetail?.salaryLabel || role.salary;
  }

  getDetailDescription(role: RecommendedRole): string {
    return this.cleanDescription(
      this.selectedDetail?.descriptionText || this.selectedDetail?.descriptionExcerpt
    ) || role.companyInsight;
  }

  shouldShowDescriptionToggle(role: RecommendedRole): boolean {
    return this.getDetailDescription(role).length > 700;
  }

  toggleDescription(): void {
    this.descriptionExpanded = !this.descriptionExpanded;
  }

  getCardFacts(role: RecommendedRole): JobFact[] {
    return [
      { icon: 'payments', label: 'Salary', value: role.salary },
      { icon: this.isRemoteRole(role) ? 'public' : 'location_on', label: 'Location', value: role.workMode },
      { icon: 'schedule', label: 'Posted', value: role.posted },
    ];
  }

  getDetailFacts(role: RecommendedRole): JobFact[] {
    return [
      { icon: 'payments', label: 'Salary', value: this.getDetailSalary(role) },
      { icon: this.isRemoteRole(role) ? 'public' : 'apartment', label: 'Work mode', value: role.workMode },
      { icon: 'schedule', label: 'Posted', value: role.posted },
      { icon: 'stacked_line_chart', label: 'Total comp', value: this.getCompBenchmarkLabel(role) },
    ];
  }

  getDescriptionSections(role: RecommendedRole): JobDescriptionSection[] {
    const description = this.getDetailDescription(role);
    const sentences = this.toSentences(description);
    if (sentences.length === 0) {
      return [];
    }

    const overview = this.firstSentences(sentences, 2);
    const responsibilities = this.sentencesMatching(sentences, [
      'you will',
      "you'll",
      'responsible',
      'build',
      'design',
      'lead',
      'partner',
      'collaborate',
      'develop',
      'own',
    ], 4);
    const qualifications = this.sentencesMatching(sentences, [
      'experience',
      'years',
      'degree',
      'knowledge',
      'skills',
      'proficiency',
      'qualification',
      'requirements',
      'nice to have',
      'b.s.',
      'm.s.',
      'phd',
    ], 4);
    const compensation = this.sentencesMatching(sentences, [
      'salary',
      'compensation',
      'pay range',
      'benefits',
      'bonus',
      'equity',
      '401',
      'medical',
      'dental',
      'vision',
      'paid time',
    ], 4);
    const hiring = this.sentencesMatching(sentences, [
      'equal opportunity',
      'accommodation',
      'background',
      'authorized',
      'privacy',
      'non-discrimination',
      'applicant',
    ], 2);

    return [
      { icon: 'flag', title: 'Quick read', body: overview },
      { icon: 'checklist', title: 'What you would do', body: responsibilities || this.firstSentences(sentences.slice(2), 3) },
      { icon: 'person_search', title: 'What they want', body: qualifications },
      { icon: 'payments', title: 'Pay and benefits', body: compensation },
      { icon: 'gavel', title: 'Hiring notes', body: hiring },
    ].filter((section) => section.body);
  }

  private refreshSelectionAfterFilter(): void {
    const selectedJob = this.selectedJob;
    if (selectedJob && this.selectedRole && this.getRoleKey(selectedJob) !== this.getRoleKey(this.selectedRole)) {
      this.selectedRole = null;
    }

    this.loadDetailForRole(selectedJob);
  }

  private loadDetailForRole(role: RecommendedRole | null): void {
    const requestId = ++this.activeDetailRequest;
    this.selectedDetail = null;
    this.detailError = false;
    this.descriptionExpanded = false;

    if (!role || !role.sourceType || !role.sourceBoardToken || !role.externalJobId) {
      this.detailLoading = false;
      return;
    }

    const key = this.getRoleKey(role);
    const cachedDetail = this.detailCache.get(key);
    if (cachedDetail) {
      this.selectedDetail = cachedDetail;
      this.detailLoading = false;
      return;
    }

    this.detailLoading = true;
    this.candidatePortalService.getExternalJobDetail(role.sourceType, role.sourceBoardToken, role.externalJobId).pipe(
      timeout(6000),
      catchError(() => of(null)),
      takeUntil(this.destroy$)
    ).subscribe((detail) => {
      if (requestId !== this.activeDetailRequest) {
        return;
      }

      this.detailLoading = false;
      if (!detail) {
        this.detailError = true;
        this.changeDetectorRef.detectChanges();
        return;
      }

      this.detailCache.set(key, detail);
      this.selectedDetail = detail;
      if (detail.companyName && detail.companyName !== role.company) {
        role.company = detail.companyName;
      }
      if (detail.companyLogoUrl && !role.companyLogoUrl) {
        role.companyLogoUrl = detail.companyLogoUrl;
      }
      if (detail.companyDomain && !role.companyDomain) {
        role.companyDomain = detail.companyDomain;
      }
      this.changeDetectorRef.detectChanges();
    });
  }

  private resetVisibleRoles(): void {
    this.visibleRoleCount = this.rolePageSize;
  }

  private getCompBenchmarkLabel(role: RecommendedRole): string {
    if (this.selectedDetail?.totalCompLabel) {
      return this.selectedDetail.totalCompLabel;
    }
    if (role.totalCompLabel) {
      return role.totalCompLabel;
    }
    if (this.isSalaryMissing(role)) {
      return 'Benchmark needed';
    }

    return 'Base listed';
  }

  private getRoleKey(role: RecommendedRole): string {
    return role.sourceJobId || role.externalJobId || `${role.company}-${role.title}-${role.location}`;
  }

  private isRemoteRole(role: RecommendedRole): boolean {
    return role.workMode.toLowerCase().includes('remote') || role.location.toLowerCase().includes('remote');
  }

  private isSalaryMissing(role: RecommendedRole): boolean {
    return !role.salary || role.salary.toLowerCase().includes('not listed');
  }

  private getPostedAgeDays(role: RecommendedRole): number {
    const posted = role.posted.toLowerCase();
    if (posted.includes('just') || posted.includes('hour') || posted.includes('h ago')) {
      return 0;
    }

    const dayMatch = posted.match(/(\d+)\s*d/);
    if (dayMatch) {
      return Number(dayMatch[1]);
    }

    const weekMatch = posted.match(/(\d+)\s*w/);
    if (weekMatch) {
      return Number(weekMatch[1]) * 7;
    }

    return Number.POSITIVE_INFINITY;
  }

  private cleanDescription(description?: string): string {
    if (!description) {
      return '';
    }

    return description
      .replace(/&amp;/g, '&')
      .replace(/&nbsp;/g, ' ')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;|&#x27;|&apos;/g, "'")
      .replace(/&mdash;|&ndash;/g, '-')
      .replace(/&rsquo;|&lsquo;/g, "'")
      .replace(/&rdquo;|&ldquo;/g, '"')
      .replace(/<[^>]+>/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  private toSentences(description: string): string[] {
    return description
      .replace(/\s+/g, ' ')
      .split(/(?<=[.!?])\s+(?=[A-Z0-9])/)
      .map((sentence) => sentence.trim())
      .filter((sentence) => sentence.length > 24);
  }

  private sentencesMatching(sentences: string[], keywords: string[], limit: number): string {
    const matches = sentences.filter((sentence) => {
      const normalized = sentence.toLowerCase();
      return keywords.some((keyword) => normalized.includes(keyword));
    });

    return this.firstSentences(matches, limit);
  }

  private firstSentences(sentences: string[], limit: number): string {
    return this.clampText(sentences.slice(0, limit).join(' '), 620);
  }

  private clampText(text: string, maxLength: number): string {
    if (text.length <= maxLength) {
      return text;
    }

    const clipped = text.slice(0, maxLength);
    const lastSentence = Math.max(clipped.lastIndexOf('.'), clipped.lastIndexOf('!'), clipped.lastIndexOf('?'));
    if (lastSentence > 160) {
      return clipped.slice(0, lastSentence + 1);
    }

    return `${clipped.trim()}...`;
  }

  private sourceLabel(source: string): string {
    const sourceName = this.roles.find((role) => (role.sourceType || role.sourceName || '').toUpperCase() === source)?.sourceName;
    if (sourceName) {
      return sourceName;
    }

    return source.charAt(0) + source.slice(1).toLowerCase();
  }
}
