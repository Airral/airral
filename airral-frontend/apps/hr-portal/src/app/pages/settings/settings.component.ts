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
      title: 'Hiring stages and scorecards',
      description: 'Define custom hiring stages and evaluation scorecards for your workflow.',
      route: '/settings/hiring-stages'
    },
    {
      title: 'Panel templates and interview kits',
      description: 'Create reusable interview templates and question banks.',
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
