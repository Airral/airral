// apps/hr-portal/src/app/pages/candidates/components/activity-log.component.ts
import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

interface Activity {
  id: number;
  action: string;
  user: string;
  timestamp: Date;
  details?: string;
}

@Component({
  selector: 'app-activity-log',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="activity-log">
      <h3>Activity Timeline</h3>

      <div class="timeline">
        <div class="timeline-item" *ngFor="let activity of activities">
          <div class="timeline-marker"></div>
          <div class="timeline-content">
            <div class="activity-header">
              <strong>{{ activity.action }}</strong>
              <span class="activity-time">{{ activity.timestamp | date: 'short' }}</span>
            </div>
            <p class="activity-user">by {{ activity.user }}</p>
            <p *ngIf="activity.details" class="activity-details">{{ activity.details }}</p>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .activity-log {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 1.5rem;
    }

    h3 {
      margin: 0 0 1.5rem;
      font-size: 1.1rem;
      color: #0f172a;
    }

    .timeline {
      position: relative;
      padding-left: 2rem;
    }

    .timeline::before {
      content: '';
      position: absolute;
      left: 0.5rem;
      top: 0;
      bottom: 0;
      width: 2px;
      background: #e2e8f0;
    }

    .timeline-item {
      position: relative;
      padding-bottom: 1.5rem;
    }

    .timeline-item:last-child {
      padding-bottom: 0;
    }

    .timeline-marker {
      position: absolute;
      left: -1.5rem;
      top: 0.25rem;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #0f9d78;
      border: 2px solid white;
      box-shadow: 0 0 0 2px #0f9d78;
    }

    .timeline-content {
      background: #f8fafc;
      padding: 1rem;
      border-radius: 8px;
    }

    .activity-header {
      display: flex;
      justify-content: space-between;
      align-items: start;
      margin-bottom: 0.25rem;
    }

    .activity-header strong {
      color: #0f172a;
    }

    .activity-time {
      font-size: 0.85rem;
      color: #64748b;
    }

    .activity-user {
      margin: 0.25rem 0;
      font-size: 0.9rem;
      color: #64748b;
    }

    .activity-details {
      margin: 0.5rem 0 0;
      padding: 0.75rem;
      background: white;
      border-radius: 6px;
      font-size: 0.9rem;
      color: #475569;
    }
  `]
})
export class ActivityLogComponent {
  @Input() candidateId!: number;

  activities: Activity[] = [
    {
      id: 1,
      action: 'Application submitted',
      user: 'System',
      timestamp: new Date('2026-04-18T09:00:00')
    },
    {
      id: 2,
      action: 'Status changed to Under Review',
      user: 'Sarah HR',
      timestamp: new Date('2026-04-19T11:30:00')
    },
    {
      id: 3,
      action: 'Note added',
      user: 'Sarah HR',
      timestamp: new Date('2026-04-20T10:30:00'),
      details: 'Strong technical background. Impressive portfolio with React projects.'
    },
    {
      id: 4,
      action: 'Status changed to Shortlisted',
      user: 'Mike Manager',
      timestamp: new Date('2026-04-21T14:00:00')
    },
    {
      id: 5,
      action: 'Interview scheduled',
      user: 'Sarah HR',
      timestamp: new Date('2026-04-22T16:45:00'),
      details: 'Technical interview on April 26, 2026 at 2:00 PM'
    }
  ];
}
