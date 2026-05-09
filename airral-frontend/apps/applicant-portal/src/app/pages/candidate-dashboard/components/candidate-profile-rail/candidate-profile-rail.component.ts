import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CandidateApplicationView, CandidateProfile } from '@airral/shared-types';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-candidate-profile-rail',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatButtonModule, MatProgressBarModule, MatDividerModule, MatIconModule],
  templateUrl: './candidate-profile-rail.component.html',
  styleUrls: ['./candidate-profile-rail.component.css']
})
export class CandidateProfileRailComponent {
  @Input() profile: CandidateProfile | null = null;
  @Input() applications: CandidateApplicationView[] = [];

  @Output() editProfile = new EventEmitter<void>();
  @Output() logout = new EventEmitter<void>();

  getProfileCompletion(): number {
    if (!this.profile) {
      return 0;
    }

    let score = 40;
    if (this.profile.phone) score += 20;
    if (this.profile.location) score += 20;
    if (this.profile.resume) score += 20;
    return score;
  }

  getActiveApplicationsCount(): number {
    return this.applications.filter((app) => !app.status.toLowerCase().includes('rejected')).length;
  }

  getOfferCount(): number {
    return this.applications.filter((app) => !!app.currentOffer).length;
  }

  getInterviewCount(): number {
    return this.applications.reduce((total, app) => total + app.interviews.length, 0);
  }

  getInitials(): string {
    const first = this.profile?.firstName?.charAt(0)?.toUpperCase() || 'A';
    const last = this.profile?.lastName?.charAt(0)?.toUpperCase() || 'P';
    return `${first}${last}`;
  }
}
