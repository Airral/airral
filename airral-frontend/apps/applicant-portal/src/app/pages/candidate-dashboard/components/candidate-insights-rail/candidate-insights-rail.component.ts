import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CandidateApplicationView, CandidateProfile } from '@airral/shared-types';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';

@Component({
  selector: 'app-candidate-insights-rail',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatButtonModule, MatDividerModule, MatIconModule, MatListModule],
  templateUrl: './candidate-insights-rail.component.html',
  styleUrls: ['./candidate-insights-rail.component.css']
})
export class CandidateInsightsRailComponent {
  @Input() profile: CandidateProfile | null = null;
  @Input() applications: CandidateApplicationView[] = [];

  @Output() openJobsBoard = new EventEmitter<void>();
  @Output() editProfile = new EventEmitter<void>();

  readonly engagementStats = [
    { label: 'People helped', value: 12, tone: 'mint' },
    { label: 'Comments received', value: 7, tone: 'blue' },
    { label: 'Profile views', value: 18, tone: 'gold' }
  ];

  readonly trendingTopics = [
    { name: 'InterviewStories', posts: 84 },
    { name: 'CareerSwitch', posts: 62 },
    { name: 'ResumeWins', posts: 49 },
    { name: 'SalaryNegotiation', posts: 37 }
  ];

  readonly liveSessions = [
    {
      title: 'Ask HR: Resume Feedback Live',
      host: 'AIRRAL Talent Team',
      time: 'Today · 6:00 PM'
    },
    {
      title: 'Mock Interview Circle',
      host: 'Community Mentor Group',
      time: 'Tomorrow · 7:30 PM'
    }
  ];

  getPendingActionItems(): string[] {
    const items: string[] = [];

    if (this.getProfileCompletion() < 100) {
      items.push('Complete your profile to improve recruiter response rates.');
    }

    const offerPending = this.applications.find(
      (app) => app.currentOffer && app.currentOffer.status === 'SENT'
    );
    if (offerPending?.currentOffer) {
      items.push(`Respond to your ${offerPending.currentOffer.jobTitle} offer before ${this.formatDate(offerPending.currentOffer.acceptanceDeadline)}.`);
    }

    const interviewPending = this.applications.find((app) =>
      app.interviews.some((interview) => interview.status === 'SCHEDULED')
    );
    if (interviewPending) {
      items.push(`You have an interview scheduled for ${interviewPending.jobTitle}.`);
    }

    if (items.length === 0) {
      items.push('No urgent actions right now. Keep exploring roles and opportunities.');
    }

    return items;
  }

  private getProfileCompletion(): number {
    if (!this.profile) {
      return 0;
    }

    let score = 40;
    if (this.profile.phone) score += 20;
    if (this.profile.location) score += 20;
    if (this.profile.resume) score += 20;
    return score;
  }

  private formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    });
  }
}
