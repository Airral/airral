// apps/hr-portal/src/app/pages/settings/tier-settings.component.ts
import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { OrganizationService } from '@airral/shared-utils';
import { OrganizationTier } from '@airral/shared-types';

@Component({
  selector: 'app-tier-settings',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="tier-settings">
      <h2>Hiring Mode</h2>
      <p class="description">Choose the experience that fits your team size and hiring volume.</p>

      <div class="tier-options">
        <div
          class="tier-option"
          [class.active]="currentTier === tiers.QUICK_HIRE"
          (click)="selectTier(tiers.QUICK_HIRE)"
        >
          <div class="tier-header">
            <h3>Quick Hire</h3>
            <span class="tier-badge" *ngIf="currentTier === tiers.QUICK_HIRE">Current</span>
          </div>
          <p class="tier-subtitle">Fast and simple hiring for small teams</p>
          <ul class="tier-features">
            <li>✓ Job posting & application tracking</li>
            <li>✓ Interview scheduling & feedback</li>
            <li>✓ Notes, comments & activity log</li>
            <li>✓ Email notifications & templates</li>
            <li>✓ Resume storage & search</li>
            <li>✓ Compliance-ready record-keeping</li>
          </ul>
          <p class="tier-best-for">Best for: 1-15 employees, 1-10 open jobs</p>
        </div>

        <div
          class="tier-option"
          [class.active]="currentTier === tiers.PROFESSIONAL"
          [class.recommended]="isRecommended(tiers.PROFESSIONAL)"
          (click)="selectTier(tiers.PROFESSIONAL)"
        >
          <div class="tier-header">
            <h3>Professional</h3>
            <span class="tier-badge recommended" *ngIf="isRecommended(tiers.PROFESSIONAL)">Recommended</span>
            <span class="tier-badge" *ngIf="currentTier === tiers.PROFESSIONAL">Current</span>
          </div>
          <p class="tier-subtitle">Structured process for growing companies</p>
          <ul class="tier-features">
            <li>✓ Everything in Quick Hire</li>
            <li>✓ Pipeline board & custom stages</li>
            <li>✓ Analytics & reporting dashboards</li>
            <li>✓ Interview scorecards & evaluation</li>
            <li>✓ Offer management & tracking</li>
            <li>✓ Team collaboration & approvals</li>
            <li>✓ Bulk actions & advanced tools</li>
          </ul>
          <p class="tier-best-for">Best for: 15-100 employees, 10-30 open jobs</p>
        </div>

        <div
          class="tier-option"
          [class.active]="currentTier === tiers.ENTERPRISE"
          [class.disabled]="true"
        >
          <div class="tier-header">
            <h3>Enterprise</h3>
            <span class="tier-badge coming-soon">Coming Soon</span>
          </div>
          <p class="tier-subtitle">Advanced features for large organizations</p>
          <ul class="tier-features">
            <li>✓ Everything in Professional</li>
            <li>✓ Multi-entity support</li>
            <li>✓ Advanced analytics</li>
            <li>✓ Integrations (HRIS, Calendar)</li>
            <li>✓ Compliance tools</li>
            <li>✓ API access</li>
          </ul>
          <p class="tier-best-for">Best for: 100+ employees, 20+ open jobs</p>
        </div>
      </div>

      <div class="tier-actions">
        <button
          *ngIf="hasChanges"
          (click)="confirmChange()"
          class="btn-primary"
        >
          Switch to {{ selectedTierName }}
        </button>
        <button
          *ngIf="hasChanges"
          (click)="cancelChange()"
          class="btn-ghost"
        >
          Cancel
        </button>
      </div>
    </div>
  `,
  styles: [`
    .tier-settings {
      max-width: 1200px;
    }

    h2 {
      font-size: 1.8rem;
      margin: 0 0 0.5rem;
      color: #0f172a;
    }

    .description {
      color: #64748b;
      margin: 0 0 2rem;
    }

    .tier-options {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
      gap: 1.5rem;
      margin-bottom: 2rem;
    }

    .tier-option {
      background: white;
      border: 2px solid #e2e8f0;
      border-radius: 12px;
      padding: 1.5rem;
      cursor: pointer;
      transition: all 0.2s;
    }

    .tier-option:hover:not(.disabled) {
      border-color: #0f9d78;
      transform: translateY(-2px);
      box-shadow: 0 4px 12px rgba(15, 157, 120, 0.1);
    }

    .tier-option.active {
      border-color: #0f9d78;
      background: linear-gradient(135deg, #f0fdf4, #dcfce7);
    }

    .tier-option.recommended {
      border-color: #3b82f6;
    }

    .tier-option.disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .tier-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 0.5rem;
    }

    .tier-header h3 {
      font-size: 1.3rem;
      margin: 0;
      color: #0f172a;
    }

    .tier-badge {
      background: #0f9d78;
      color: white;
      padding: 0.25rem 0.75rem;
      border-radius: 12px;
      font-size: 0.75rem;
      font-weight: 600;
      text-transform: uppercase;
    }

    .tier-badge.recommended {
      background: #3b82f6;
    }

    .tier-badge.coming-soon {
      background: #94a3b8;
    }

    .tier-subtitle {
      color: #64748b;
      margin: 0 0 1rem;
      font-size: 0.95rem;
    }

    .tier-features {
      list-style: none;
      padding: 0;
      margin: 0 0 1rem;
    }

    .tier-features li {
      padding: 0.4rem 0;
      color: #475569;
      font-size: 0.9rem;
    }

    .tier-best-for {
      margin: 1rem 0 0;
      padding-top: 1rem;
      border-top: 1px solid #e2e8f0;
      font-size: 0.85rem;
      color: #64748b;
      font-style: italic;
    }

    .tier-actions {
      display: flex;
      gap: 1rem;
    }

    .btn-primary {
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

    .btn-primary:hover {
      background: #0b6b52;
    }

    .btn-ghost {
      background: transparent;
      color: #64748b;
      border: 1px solid #cbd5e1;
      padding: 0.75rem 1.5rem;
      border-radius: 8px;
      font-size: 1rem;
      cursor: pointer;
      transition: all 0.2s;
    }

    .btn-ghost:hover {
      border-color: #94a3b8;
      color: #475569;
    }
  `]
})
export class TierSettingsComponent {
  private readonly orgService = inject(OrganizationService);

  readonly tiers = OrganizationTier;
  selectedTier: OrganizationTier | null = null;

  get currentTier(): OrganizationTier {
    return this.orgService.tier;
  }

  get hasChanges(): boolean {
    return this.selectedTier !== null && this.selectedTier !== this.currentTier;
  }

  get selectedTierName(): string {
    return this.selectedTier ? this.orgService.getTierName(this.selectedTier) : '';
  }

  selectTier(tier: OrganizationTier): void {
    if (tier === OrganizationTier.ENTERPRISE) {
      alert('Enterprise tier is coming soon! Stay tuned.');
      return;
    }
    this.selectedTier = tier;
  }

  isRecommended(tier: OrganizationTier): boolean {
    const org = this.orgService.organization;
    if (!org.employeeCount || !org.activeJobs) {
      return false;
    }

    // Recommend Professional if they have >5 employees or >3 jobs
    return tier === OrganizationTier.PROFESSIONAL &&
           (org.employeeCount > 5 || org.activeJobs > 3);
  }

  confirmChange(): void {
    if (!this.selectedTier) return;

    const tierName = this.orgService.getTierName(this.selectedTier);
    if (confirm(`Switch to ${tierName} mode?`)) {
      this.orgService.setTier(this.selectedTier);
    }
  }

  cancelChange(): void {
    this.selectedTier = null;
  }
}
