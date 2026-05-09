import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Referral, ReferralApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';

interface ReferralItem {
  id: number;
  candidate: string;
  role: string;
  department: string;
  status: 'Submitted' | 'Under Review' | 'Interview' | 'Offer Extended' | 'Hired' | 'Declined';
  reward: string;
  submittedDate: string;
  referredBy?: string;
}

@Component({
  selector: 'app-referrals',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './referrals.component.html',
  styleUrl: './referrals.component.css',
})
export class ReferralsComponent implements OnInit {
  private readonly referralApi = inject(ReferralApiService);
  private readonly authService = inject(AuthService);

  filterStatus = 'All';
  readonly statusOptions = ['All', 'Submitted', 'Under Review', 'Interview', 'Offer Extended', 'Hired', 'Declined'];
  allReferrals: ReferralItem[] = [];
  loading = true;
  error: string | null = null;

  ngOnInit(): void {
    this.loadReferrals();
  }

  loadReferrals(): void {
    this.loading = true;
    this.error = null;

    const role = this.authService.getCurrentUser()?.role?.toUpperCase() || '';
    const isHr = role === 'HR_MANAGER' || role === 'ADMIN';
    const request$ = isHr
      ? this.referralApi.getOrganizationReferrals()
      : this.referralApi.getMyReferrals();

    request$.subscribe({
      next: (referrals) => {
        this.allReferrals = referrals.map((referral) => this.toReferralItem(referral));
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load referrals';
        this.loading = false;
      },
    });
  }

  get referrals(): ReferralItem[] {
    if (this.filterStatus === 'All') {
      return this.allReferrals;
    }
    return this.allReferrals.filter(r => r.status === this.filterStatus);
  }

  get totalEarnings(): number {
    return this.allReferrals
      .filter(r => r.status === 'Hired')
      .reduce((sum, r) => sum + parseInt(r.reward.replace(/[$,]/g, ''), 10), 0);
  }

  get pendingEarnings(): number {
    return this.allReferrals
      .filter(r => ['Interview', 'Offer Extended'].includes(r.status))
      .reduce((sum, r) => sum + parseInt(r.reward.replace(/[$,]/g, ''), 10), 0);
  }

  getStatusClass(status: string): string {
    const map: Record<string, string> = {
      Submitted: 'status-submitted',
      'Under Review': 'status-review',
      Interview: 'status-interview',
      'Offer Extended': 'status-offer',
      Hired: 'status-hired',
      Declined: 'status-declined'
    };
    return map[status] || '';
  }

  private toReferralItem(referral: Referral): ReferralItem {
    return {
      id: referral.id,
      candidate: referral.referredName,
      role: referral.jobTitle || 'Unknown Role',
      department: 'General',
      status: this.mapStatus(referral.status),
      reward: `$${Math.round(referral.bonusAmount || 0).toLocaleString()}`,
      submittedDate: referral.submittedAt ? new Date(referral.submittedAt).toISOString().slice(0, 10) : '--',
      referredBy: referral.referredByName || 'Unknown',
    };
  }

  private mapStatus(status: string): ReferralItem['status'] {
    const normalized = status?.toUpperCase();
    const statusMap: Record<string, ReferralItem['status']> = {
      SUBMITTED: 'Submitted',
      UNDER_REVIEW: 'Under Review',
      INTERVIEW_SCHEDULED: 'Interview',
      INTERVIEWED: 'Interview',
      OFFER_EXTENDED: 'Offer Extended',
      HIRED: 'Hired',
      REJECTED: 'Declined',
      DECLINED: 'Declined',
      WITHDRAWN: 'Declined',
    };

    return statusMap[normalized] || 'Submitted';
  }
}
