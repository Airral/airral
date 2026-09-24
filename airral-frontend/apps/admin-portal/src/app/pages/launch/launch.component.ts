import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LaunchApplicant, LaunchDay, LaunchMetrics, LaunchMetricsService } from '../../services/launch-metrics.service';

interface FunnelStep {
  label: string;
  hint: string;
  count: number;
  /** Share of everyone who signed up, 0-100. */
  ofSignups: number;
  /** People lost since the step before. */
  dropped: number;
}

interface DayBar {
  day: LaunchDay;
  value: number;
  /** Bar height as a share of the busiest day, 0-100. */
  height: number;
  label: string;
}

const WINDOWS = [
  { days: 1, label: 'Today' },
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
];

/**
 * Is launch working: did people come, sign up, and get as far as a job?
 *
 * <p>The funnel follows the people who signed up in the window through every
 * step they have reached since, so each bar is a count of real accounts, not a
 * guess from traffic. Visitors are counted separately: they are anonymous, so
 * they cannot be joined to sign-ups.
 */
@Component({
  selector: 'app-launch',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './launch.component.html',
  styleUrls: ['./launch.component.css'],
})
export class LaunchComponent implements OnInit {
  private readonly service = inject(LaunchMetricsService);

  readonly windows = WINDOWS;
  readonly days = signal(7);
  readonly metrics = signal<LaunchMetrics | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly showTable = signal(false);
  readonly hovered = signal<{ chart: 'visitors' | 'signups'; index: number } | null>(null);

  readonly steps = computed<FunnelStep[]>(() => {
    const f = this.metrics()?.funnel;
    if (!f) return [];
    const raw: [string, string, number][] = [
      ['Signed up', 'Made an applicant account', f.signedUp],
      ['Verified email', 'Opened the link we emailed', f.verified],
      ['Uploaded a resume', 'The core of the product', f.uploadedResume],
      ['Ran a match score', 'Resume checked against a job', f.ranMatch],
      ['Saved a job', 'Kept a job to come back to', f.savedJob],
      ['Clicked Apply', "Left for the employer's application", f.clickedApply],
      ['Marked as applied', 'Moved a job to Applied in their tracker', f.trackedApplied],
    ];
    return raw.map(([label, hint, count], i) => ({
      label,
      hint,
      count,
      ofSignups: f.signedUp ? Math.round((count / f.signedUp) * 100) : 0,
      dropped: i === 0 ? 0 : Math.max(0, raw[i - 1][2] - count),
    }));
  });

  /** The single step where the most people stop -- the thing to fix first. */
  readonly biggestDrop = computed(() => {
    const steps = this.steps();
    let worst: { from: string; to: string; people: number } | null = null;
    for (let i = 1; i < steps.length; i++) {
      if (steps[i].dropped > 0 && (!worst || steps[i].dropped > worst.people)) {
        worst = { from: steps[i - 1].label, to: steps[i].label, people: steps[i].dropped };
      }
    }
    return worst;
  });

  readonly visitorBars = computed(() => this.bars((d) => d.visitors, 'visitor'));
  readonly signupBars = computed(() => this.bars((d) => d.signups, 'sign-up'));
  readonly maxReferrer = computed(() => Math.max(1, ...(this.metrics()?.topReferrers ?? []).map((r) => r.visits)));

  ngOnInit(): void {
    this.load();
  }

  choose(days: number): void {
    if (days === this.days()) return;
    this.days.set(days);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.service.load(this.days()).subscribe({
      next: (metrics) => {
        this.metrics.set(metrics);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.status === 403 ? 'Only platform admins can see launch numbers.' : 'Could not load launch numbers. Try again.');
        this.loading.set(false);
      },
    });
  }

  percent(part: number, whole: number): string {
    return whole ? `${Math.round((part / whole) * 100)}%` : '—';
  }

  /** The first step this person has not reached yet, or null if they got all the way. */
  stuckAt(a: LaunchApplicant): string | null {
    if (!a.verified) return 'Verify email';
    if (!a.resumes) return 'Upload resume';
    if (!a.matches) return 'Run a match';
    if (!a.savedJobs && !a.applyClicks) return 'Save or apply';
    if (!a.applyClicks) return 'Click Apply';
    return null;
  }

  lastActive(a: LaunchApplicant): string | null {
    const times = [a.lastSeenAt, a.lastLoginAt ? `${a.lastLoginAt}Z` : null, `${a.signedUpAt}Z`]
      .filter((t): t is string => !!t)
      .map((t) => new Date(t).getTime())
      .filter((t) => !Number.isNaN(t));
    return times.length ? new Date(Math.max(...times)).toISOString() : null;
  }

  /** Account timestamps are UTC without a zone; mark them so the date pipe does not shift them. */
  utc(value: string | null): string | null {
    return value ? `${value}Z` : null;
  }

  /** The busiest day's value, so the unlabelled bars have a scale. */
  peak(bars: DayBar[]): number {
    return Math.max(0, ...bars.map((b) => b.value));
  }

  hover(chart: 'visitors' | 'signups', index: number | null): void {
    this.hovered.set(index === null ? null : { chart, index });
  }

  private bars(pick: (d: LaunchDay) => number, noun: string): DayBar[] {
    const days = this.metrics()?.daily ?? [];
    const max = Math.max(1, ...days.map(pick));
    return days.map((day) => {
      const value = pick(day);
      return {
        day,
        value,
        height: value ? Math.max(4, (value / max) * 100) : 0,
        label: `${value} ${noun}${value === 1 ? '' : 's'}`,
      };
    });
  }
}
