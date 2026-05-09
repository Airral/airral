import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ReferralApiService, UserApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';
import { forkJoin } from 'rxjs';

interface TeamMemberReview {
  id: number;
  name: string;
  team: string;
  referrals: number;
  interviewsCompleted: number;
  interviewPanel: string;
  score: number;
  lastActivity: string;
}

@Component({
  selector: 'app-team-review',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './team-review.component.html',
  styleUrl: './team-review.component.css',
})
export class TeamReviewComponent implements OnInit {
  private readonly userApi = inject(UserApiService);
  private readonly referralApi = inject(ReferralApiService);
  private readonly authService = inject(AuthService);

  selectedTeam = 'All Teams';
  sortBy: 'name' | 'referrals' | 'score' = 'score';

  teams: string[] = ['All Teams'];
  allMembers: TeamMemberReview[] = [];
  loading = true;
  error: string | null = null;

  ngOnInit(): void {
    this.loadTeamReview();
  }

  loadTeamReview(): void {
    this.loading = true;
    this.error = null;

    const role = this.authService.getCurrentUser()?.role?.toUpperCase() || '';
    const isHr = role === 'HR_MANAGER' || role === 'ADMIN';
    const referrals$ = isHr ? this.referralApi.getOrganizationReferrals() : this.referralApi.getMyReferrals();

    forkJoin({
      users: this.userApi.getAllUsers(),
      referrals: referrals$,
    }).subscribe({
      next: ({ users, referrals }) => {
        const referralCountByUser = referrals.reduce<Record<number, number>>((acc, referral) => {
          const key = referral.referredById;
          acc[key] = (acc[key] || 0) + 1;
          return acc;
        }, {});

        this.allMembers = users
          .filter((user) => user.isActive)
          .filter((user) => ['HR_MANAGER', 'MANAGER', 'EMPLOYEE', 'ADMIN'].includes((user.role || '').toUpperCase()))
          .map((user) => {
            const referralsForUser = referralCountByUser[user.id] || 0;
            const score = Math.min(100, referralsForUser * 10);
            return {
              id: user.id,
              name: `${user.firstName || ''} ${user.lastName || ''}`.trim() || user.email,
              team: user.department || 'General',
              referrals: referralsForUser,
              interviewsCompleted: 0,
              interviewPanel: 'N/A',
              score,
              lastActivity: this.formatLastActivity(user.lastLoginAt || user.createdAt),
            };
          });

        this.teams = ['All Teams', ...new Set(this.allMembers.map((m) => m.team))];
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load team review data';
        this.loading = false;
      },
    });
  }

  get members(): TeamMemberReview[] {
    let filtered = this.allMembers;

    if (this.selectedTeam !== 'All Teams') {
      filtered = filtered.filter(m => m.team === this.selectedTeam);
    }

    return [...filtered].sort((a, b) => {
      if (this.sortBy === 'name') return a.name.localeCompare(b.name);
      if (this.sortBy === 'referrals') return b.referrals - a.referrals;
      if (this.sortBy === 'score') return b.score - a.score;
      return 0;
    });
  }

  get averageScore(): string {
    if (this.members.length === 0) return '0.0';
    return (this.members.reduce((sum, m) => sum + m.score, 0) / this.members.length).toFixed(1);
  }

  filterByTeam(team: string): void {
    this.selectedTeam = team;
  }

  setSortBy(field: 'name' | 'referrals' | 'score'): void {
    this.sortBy = field;
  }

  private formatLastActivity(date?: string): string {
    if (!date) return 'No activity';
    const activityDate = new Date(date);
    const diffMs = Date.now() - activityDate.getTime();
    const diffDays = Math.max(0, Math.floor(diffMs / (1000 * 60 * 60 * 24)));

    if (diffDays === 0) return 'Today';
    if (diffDays === 1) return '1 day ago';
    return `${diffDays} days ago`;
  }
}
