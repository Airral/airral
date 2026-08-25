import { Component, OnInit, OnDestroy } from '@angular/core';
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
      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">Total Companies</div>
          <div class="stat-value">{{ statistics?.totalCompanies || 0 }}</div>
          <div class="stat-description">with active job postings</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">Total Jobs</div>
          <div class="stat-value">{{ statistics?.totalJobs || 0 }}</div>
          <div class="stat-description">currently open</div>
        </div>
      </div>
      <div *ngIf="error" class="error-message">{{ error }}</div>
      <div *ngIf="loading" class="loading">Loading statistics...</div>
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
      color: #d32f2f;
      padding: 10px;
      background-color: #ffebee;
      border-radius: 4px;
      margin-bottom: 10px;
    }

    .loading {
      text-align: center;
      color: #666;
      padding: 20px;
    }
  `]
})
export class PublicStatisticsComponent implements OnInit, OnDestroy {
  statistics: PublicStatistics | null = null;
  loading = true;
  error: string | null = null;
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
    this.loading = true;
    this.error = null;
    this.statisticsService.getPublicStatistics()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (stats) => {
          this.statistics = stats;
          this.loading = false;
        },
        error: (err) => {
          this.error = 'Failed to load statistics. Please try again later.';
          this.loading = false;
          console.error('Error loading statistics:', err);
        }
      });
  }
}
