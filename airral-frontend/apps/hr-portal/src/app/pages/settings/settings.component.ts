import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { Router } from '@angular/router';
import { TierSettingsComponent } from './tier-settings.component';

interface SettingSection {
  title: string;
  description: string;
  route?: string;
}

@Component({
  selector: 'app-hr-settings',
  standalone: true,
  imports: [CommonModule, TierSettingsComponent],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent {
  readonly sections: SettingSection[] = [
    {
      title: 'Company profile',
      description: 'What candidates see about you on your jobs: logo, website, industry and size.',
      route: '/settings/company'
    },
    {
      title: 'Team',
      description: 'Invite the people who hire with you, and see who has access.',
      route: '/settings/team'
    },
    {
      title: 'Departments',
      description: 'The teams your jobs and people belong to.',
      route: '/settings/departments'
    },
    {
      title: 'Hiring stages',
      description: 'Define custom hiring stages for your workflow.',
      route: '/settings/hiring-stages'
    },
    {
      title: 'Interview kits',
      description: 'The questions interviewers ask and what they rate, for each job.',
      route: '/settings/interview-kits'
    },
    {
      title: 'Role-based permissions',
      description: 'Configure who can view, edit, and approve hiring decisions.',
      route: undefined // Coming soon
    },
    {
      title: 'Email and calendar integrations',
      description: 'Connect Gmail, Outlook, LinkedIn, and other tools to streamline recruiting.',
      route: '/settings/integrations'
    },
  ];

  constructor(private router: Router) {}

  configure(section: SettingSection): void {
    if (section.route) {
      this.router.navigate([section.route]);
    } else {
      alert(`${section.title} configuration coming soon!`);
    }
  }
}
