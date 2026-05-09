// apps/hr-portal/src/app/pages/workspace-home/quick-hire-home.component.ts
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApplicationApiService, JobApiService } from '@airral/shared-api';
import { Application, Job, OrganizationTier } from '@airral/shared-types';
import { OrganizationService } from '@airral/shared-utils';

@Component({
  selector: 'app-quick-hire-home',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="quick-hire-home">
      <header class="home-header">
        <h1>Welcome to Quick Hire</h1>
        <p class="subtitle">The fastest way to hire great people</p>
      </header>

      <div class="quick-stats">
        <div class="stat-card">
          <div class="stat-value">{{ openJobs }}</div>
          <div class="stat-label">Open Jobs</div>
          <a routerLink="/jobs" class="stat-action">View all →</a>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ newApplications }}</div>
          <div class="stat-label">New Applications</div>
          <a routerLink="/candidates" class="stat-action">Review →</a>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ totalHires }}</div>
          <div class="stat-label">Hires This Month</div>
        </div>
      </div>

      <section class="next-steps-section">
        <h2>What would you like to do?</h2>
        <div class="action-grid">
          <a routerLink="/jobs" class="action-card primary">
            <span class="action-icon">📋</span>
            <h3>Post a New Job</h3>
            <p>Create and publish a job opening in minutes</p>
          </a>
          <a routerLink="/candidates" class="action-card">
            <span class="action-icon">👥</span>
            <h3>Review Candidates</h3>
            <p>See who applied and move them forward</p>
          </a>
          <a routerLink="/settings" class="action-card">
            <span class="action-icon">⚙️</span>
            <h3>Settings</h3>
            <p>Configure your workspace and preferences</p>
          </a>
        </div>
      </section>

      <section class="recent-activity" *ngIf="recentApplications.length > 0">
        <h2>Recent Activity</h2>
        <div class="activity-list">
          <div class="activity-item" *ngFor="let app of recentApplications">
            <div class="activity-icon">📨</div>
            <div class="activity-content">
              <p class="activity-title">{{ app.applicantEmail }} applied for {{ app.jobTitle }}</p>
              <p class="activity-time">{{ app.submittedAt | date: 'short' }}</p>
            </div>
            <a [routerLink]="['/candidates']" class="activity-action">Review</a>
          </div>
        </div>
      </section>

      <section class="upgrade-prompt">
        <div class="upgrade-card">
          <h3>Growing your team?</h3>
          <p>Upgrade to Professional for analytics, pipeline boards, interview scorecards, and advanced collaboration tools.</p>
          <button (click)="upgradeToProfessional()" class="btn-upgrade">Explore Professional →</button>
        </div>
      </section>
    </div>
  `,
  styles: [`
    .quick-hire-home {
      max-width: 1200px;
      margin: 0 auto;
      padding: 2rem;
    }

    .home-header {
      margin-bottom: 2rem;
    }

    .home-header h1 {
      font-size: 2rem;
      margin: 0 0 0.5rem;
      color: #0f172a;
    }

    .subtitle {
      font-size: 1.1rem;
      color: #64748b;
      margin: 0;
    }

    .quick-stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 1.5rem;
      margin-bottom: 3rem;
    }

    .stat-card {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 1.5rem;
    }

    .stat-value {
      font-size: 2.5rem;
      font-weight: 700;
      color: #0f9d78;
      margin-bottom: 0.5rem;
    }

    .stat-label {
      font-size: 0.9rem;
      color: #64748b;
      margin-bottom: 0.75rem;
    }

    .stat-action {
      color: #0f9d78;
      text-decoration: none;
      font-size: 0.9rem;
      font-weight: 500;
    }

    .stat-action:hover {
      text-decoration: underline;
    }

    .next-steps-section,
    .recent-activity,
    .upgrade-prompt {
      margin-bottom: 3rem;
    }

    .next-steps-section h2,
    .recent-activity h2 {
      font-size: 1.5rem;
      margin: 0 0 1.5rem;
      color: #0f172a;
    }

    .action-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
      gap: 1.5rem;
    }

    .action-card {
      background: white;
      border: 2px solid #e2e8f0;
      border-radius: 12px;
      padding: 2rem;
      text-decoration: none;
      transition: all 0.2s;
    }

    .action-card:hover {
      border-color: #0f9d78;
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(15, 157, 120, 0.1);
    }

    .action-card.primary {
      background: linear-gradient(135deg, #0f9d78, #0ea472);
      border-color: #0f9d78;
      color: white;
    }

    .action-icon {
      font-size: 2rem;
      display: block;
      margin-bottom: 1rem;
    }

    .action-card h3 {
      font-size: 1.2rem;
      margin: 0 0 0.5rem;
    }

    .action-card.primary h3 {
      color: white;
    }

    .action-card p {
      margin: 0;
      color: #64748b;
      font-size: 0.9rem;
    }

    .action-card.primary p {
      color: rgba(255, 255, 255, 0.9);
    }

    .activity-list {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      overflow: hidden;
    }

    .activity-item {
      display: flex;
      align-items: center;
      gap: 1rem;
      padding: 1.25rem;
      border-bottom: 1px solid #f1f5f9;
    }

    .activity-item:last-child {
      border-bottom: none;
    }

    .activity-icon {
      font-size: 1.5rem;
    }

    .activity-content {
      flex: 1;
    }

    .activity-title {
      margin: 0 0 0.25rem;
      font-weight: 500;
      color: #0f172a;
    }

    .activity-time {
      margin: 0;
      font-size: 0.85rem;
      color: #94a3b8;
    }

    .activity-action {
      color: #0f9d78;
      text-decoration: none;
      font-weight: 500;
      padding: 0.5rem 1rem;
      border: 1px solid #0f9d78;
      border-radius: 6px;
      transition: all 0.2s;
    }

    .activity-action:hover {
      background: #0f9d78;
      color: white;
    }

    .upgrade-card {
      background: linear-gradient(135deg, #f0fdf4, #dcfce7);
      border: 2px solid #86efac;
      border-radius: 12px;
      padding: 2rem;
      text-align: center;
    }

    .upgrade-card h3 {
      font-size: 1.3rem;
      margin: 0 0 0.5rem;
      color: #0f172a;
    }

    .upgrade-card p {
      margin: 0 0 1.5rem;
      color: #4b5563;
    }

    .btn-upgrade {
      background: #0f9d78;
      color: white;
      border: none;
      padding: 0.75rem 1.5rem;
      border-radius: 8px;
      font-size: 1rem;
      font-weight: 600;
      cursor: pointer;
      transition: background 0.2s;
    }

    .btn-upgrade:hover {
      background: #0b6b52;
    }
  `]
})
export class QuickHireHomeComponent {
  private readonly jobApi = inject(JobApiService);
  private readonly applicationApi = inject(ApplicationApiService);
  private readonly orgService = inject(OrganizationService);

  openJobs = 0;
  newApplications = 0;
  totalHires = 0;
  recentApplications: Application[] = [];

  ngOnInit(): void {
    this.loadStats();
  }

  loadStats(): void {
    this.jobApi.getAllJobs().subscribe(jobs => {
      this.openJobs = jobs.filter(j => j.status === 'OPEN').length;
    });

    this.applicationApi.getAllApplications().subscribe(applications => {
      this.newApplications = applications.filter(a => a.status === 'SUBMITTED').length;
      this.totalHires = applications.filter(a => a.status === 'HIRED').length;
      this.recentApplications = applications
        .sort((a, b) => new Date(b.appliedAt || b.updatedAt).getTime() - new Date(a.appliedAt || a.updatedAt).getTime())
        .slice(0, 5);
    });
  }

  upgradeToProfessional(): void {
    if (confirm('Upgrade to Professional mode for advanced features?')) {
      this.orgService.setTier(OrganizationTier.PROFESSIONAL);
    }
  }
}
