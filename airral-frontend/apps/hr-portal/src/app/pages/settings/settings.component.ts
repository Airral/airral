import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { OrganizationService } from '@airral/shared-utils';

interface SettingSection {
  title: string;
  description: string;
  route: string;
}

/**
 * The company's settings. Only screens that save what HR changes are listed:
 * hiring stages, integrations, permissions and the plan switcher only looked
 * finished, so they are gone until they work.
 */
@Component({
  selector: 'app-hr-settings',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class SettingsComponent {
  private readonly orgService = inject(OrganizationService);

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
      title: 'Interview kits',
      description: 'The questions interviewers ask and what they rate, for each job.',
      route: '/settings/interview-kits'
    },
  ];

  get planName(): string {
    return this.orgService.getTierName();
  }
}
