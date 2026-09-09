import { Component, OnInit, OnDestroy, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StatisticsService, PublicStatistics } from '../../services/statistics.service';
import { Subject } from 'rxjs';
import { takeUntil } from 'rxjs/operators';

@Component({
  selector: 'app-public-statistics',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="statistics-container">
      <h2>Platform Statistics</h2>
      <div class="stats-grid" *ngIf="statistics() as stats">
        <div class="stat-card">
          <div class="stat-label">Total Companies</div>
          <div class="stat-value">{{ stats.totalCompanies }}</div>
          <div class="stat-description">with active job postings</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Total Jobs</div>
          <div class="stat-value">{{ stats.totalJobs }}</div>
          <div class="stat-description">currently open</div>
        </div>
      </div>
      <div *ngIf="error()" class="error-message" role="alert">
        <span>{{ error() }}</span>
        <button type="button" class="retry" (click)="loadStatistics()">Try again</button>
      </div>
      <div *ngIf="loading()" class="loading">Loading statistics...</div>
    </div>
  `,
  styles: [`
    .statistics-container {
      padding: 20px;
    }

    h2 {
      margin-bottom: 20px;
      color: #333;
    }

    .stats-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
      gap: 20px;
      margin-bottom: 20px;
    }

    .stat-card {
      background: white;
      border: 1px solid #e0e0e0;
      border-radius: 8px;
      padding: 20px;
      text-align: center;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
      transition: box-shadow 0.2s;
    }

    .stat-card:hover {
      box-shadow: 0 4px 8px rgba(0, 0, 0, 0.1);
    }

    .stat-label {
      font-size: 14px;
      color: #666;
      font-weight: 500;
      margin-bottom: 10px;
    }

    .stat-value {
      font-size: 36px;
      font-weight: bold;
      color: #1a9b5f;
      margin-bottom: 10px;
    }

    .stat-description {
      font-size: 12px;
      color: #999;
    }

    .error-message {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      color: #d32f2f;
      padding: 10px;
      background-color: #ffebee;
      border-radius: 4px;
      margin-bottom: 10px;
    }

    .retry {
      flex: none;
      border: 1px solid #d32f2f;
      background: transparent;
      color: #d32f2f;
      border-radius: 4px;
      padding: 6px 12px;
      font: inherit;
      cursor: pointer;
    }

    .loading {
      text-align: center;
      color: #666;
      padding: 20px;
    }
  `]
})
export class PublicStatisticsComponent implements OnInit, OnDestroy {
  /**
   * Signals rather than plain fields: these bundles ship without zone.js, so a
   * value assigned from inside the HTTP callback never told change detection
   * anything. What was observed on /statistics was the first render frozen in
   * place -- both cards on their placeholder "0" and "Loading statistics..."
   * still on screen long after the request had already failed.
   */
  readonly statistics = signal<PublicStatistics | null>(null);
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  private destroy$ = new Subject<void>();

  constructor(private statisticsService: StatisticsService) {}

  ngOnInit(): void {
    this.loadStatistics();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  loadStatistics(): void {
    this.loading.set(true);
    this.error.set(null);
    this.statisticsService.getPublicStatistics()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats) => {
          this.statistics.set(stats);
          this.loading.set(false);
        },
        error: (err) => {
          // Before this, a failed load still painted the cards off
          // `statistics?.totalJobs || 0`, so an outage read as a genuine
          // "0 companies, 0 jobs". The grid is gated on the data rather than
          // on this flag, so a first load that fails shows nothing at all,
          // while a failed refresh keeps the last good numbers beside the
          // banner instead of blanking a screen that was already correct.
          this.error.set('Failed to load statistics. Please try again later.');
          this.loading.set(false);
          console.error('Error loading statistics:', err);
        }
      });
  }
}
