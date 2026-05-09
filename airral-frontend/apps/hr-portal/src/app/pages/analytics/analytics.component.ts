import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { forkJoin } from 'rxjs';
import { finalize, timeout } from 'rxjs/operators';
import { TierGuardService } from '@airral/shared-utils';
import { AnalyticsApiService, DashboardMetrics, PipelineAnalytics } from '@airral/shared-api';

interface FunnelStage {
  stage: string;
  count: number;
  percentage: number;
}

interface StatusRow {
  label: string;
  count: number;
  percentage: number;
}

@Component({
  selector: 'app-hr-analytics',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.css',
})
export class AnalyticsComponent implements OnInit {
  protected readonly tierGuard = inject(TierGuardService);
  private readonly analyticsApi = inject(AnalyticsApiService);

  loading = false;
  dashboard: DashboardMetrics | null = null;
  pipeline: PipelineAnalytics | null = null;

  get metrics() {
    if (!this.dashboard) return [];
    return [
      {
        label: 'Time to hire',
        value: this.dashboard.averageTimeToHire > 0 ? `${this.dashboard.averageTimeToHire} days` : 'N/A',
        delta: null,
      },
      {
        label: 'Offer acceptance',
        value: `${this.dashboard.offerAcceptanceRate}%`,
        delta: null,
      },
      {
        label: 'Pipeline conversion',
        value: `${this.dashboard.applicationToInterviewRate}%`,
        delta: null,
      },
    ];
  }

  get funnelData(): FunnelStage[] {
    if (!this.pipeline) return [];
    const total = this.pipeline.applicationsReceived || 1;
    const stages = [
      { stage: 'Applications', count: this.pipeline.applicationsReceived },
      { stage: 'Interviews', count: this.pipeline.interviewsScheduled },
      { stage: 'Offers Sent', count: this.pipeline.offersSent },
      { stage: 'Offers Accepted', count: this.pipeline.offersAccepted },
      { stage: 'Hires', count: this.pipeline.hires },
    ];
    return stages.map(s => ({
      ...s,
      percentage: Math.round((s.count / total) * 100),
    }));
  }

  get statusData(): StatusRow[] {
    if (!this.pipeline?.applicationsByStatus) return [];
    const entries = Object.entries(this.pipeline.applicationsByStatus);
    const total = entries.reduce((sum, [, v]) => sum + v, 0) || 1;
    return entries
      .sort((a, b) => b[1] - a[1])
      .map(([status, count]) => ({
        label: status.replace(/_/g, ' '),
        count,
        percentage: Math.round((count / total) * 100),
      }));
  }

  get hasProAccess(): boolean {
    return this.tierGuard.hasFeatureAccess('pro');
  }

  ngOnInit(): void {
    if (!this.hasProAccess) return;
    this.loading = true;
    forkJoin({
      dashboard: this.analyticsApi.getDashboardMetrics(),
      pipeline: this.analyticsApi.getPipelineAnalytics(),
    }).pipe(
      timeout(15000),
      finalize(() => { this.loading = false; })
    ).subscribe({
      next: ({ dashboard, pipeline }) => {
        this.dashboard = dashboard;
        this.pipeline = pipeline;
      },
      error: () => {
        this.dashboard = null;
        this.pipeline = null;
      },
    });
  }
}
