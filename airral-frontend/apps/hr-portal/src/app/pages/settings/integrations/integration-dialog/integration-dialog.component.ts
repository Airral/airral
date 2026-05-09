import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface Integration {
  id: string;
  name: string;
  description: string;
  icon: string;
  connected: boolean;
  connectedAt?: string;
  connectedEmail?: string;
  category: 'job-boards' | 'email' | 'calendar' | 'social' | 'analytics';
  features: string[];
  tier: 'all' | 'professional' | 'enterprise';
}

interface ConnectionRequirement {
  field: string;
  label: string;
  type: 'text' | 'email' | 'url' | 'info';
  placeholder?: string;
  required: boolean;
  helpText?: string;
}

@Component({
  selector: 'app-integration-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './integration-dialog.component.html',
  styleUrl: './integration-dialog.component.css',
})
export class IntegrationDialogComponent {
  @Input() integration: Integration | null = null;
  @Output() dismiss = new EventEmitter<void>();
  @Output() connect = new EventEmitter<void>();

  formData: Record<string, string> = {};

  get requirements(): ConnectionRequirement[] {
    if (!this.integration) return [];

    switch (this.integration.id) {
      case 'linkedin':
        return [
          {
            field: 'info',
            label: 'LinkedIn Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to LinkedIn to authorize access to your company page. Make sure you are an admin of your company\'s LinkedIn page.'
          },
          {
            field: 'organizationId',
            label: 'Organization ID',
            type: 'text',
            placeholder: 'Auto-filled from your account',
            required: true,
            helpText: 'Your organization ID will be automatically selected'
          }
        ];

      case 'gmail':
        return [
          {
            field: 'info',
            label: 'Gmail Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to Google to authorize Gmail access. We only request permission to send emails on your behalf.'
          },
          {
            field: 'email',
            label: 'Gmail Address',
            type: 'email',
            placeholder: 'your-email@gmail.com',
            required: true,
            helpText: 'The Gmail address to send candidate emails from'
          }
        ];

      case 'outlook':
        return [
          {
            field: 'info',
            label: 'Outlook Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to Microsoft to authorize Outlook access for email and calendar.'
          },
          {
            field: 'email',
            label: 'Outlook Email',
            type: 'email',
            placeholder: 'your-email@outlook.com',
            required: true,
            helpText: 'Your Microsoft 365 or Outlook.com email address'
          }
        ];

      case 'google-calendar':
        return [
          {
            field: 'info',
            label: 'Google Calendar Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to Google to authorize calendar access for scheduling interviews.'
          },
          {
            field: 'email',
            label: 'Google Account',
            type: 'email',
            placeholder: 'your-email@gmail.com',
            required: true,
            helpText: 'The Google account with the calendar you want to use'
          }
        ];

      case 'indeed':
        return [
          {
            field: 'publisherId',
            label: 'Indeed Publisher ID',
            type: 'text',
            placeholder: '1234567890123456',
            required: true,
            helpText: 'Get your Publisher ID from Indeed\'s Employer Dashboard'
          },
          {
            field: 'info',
            label: 'How to get Publisher ID',
            type: 'info',
            required: false,
            helpText: '1. Log in to Indeed for Employers\n2. Go to Account Settings\n3. Find "Publisher ID" under API credentials\n4. Copy and paste it here'
          }
        ];

      case 'glassdoor':
        return [
          {
            field: 'apiKey',
            label: 'Glassdoor API Key',
            type: 'text',
            placeholder: 'Your API key',
            required: true,
            helpText: 'Contact Glassdoor to get API access for your company'
          },
          {
            field: 'companyId',
            label: 'Company ID',
            type: 'text',
            placeholder: 'Your Glassdoor company ID',
            required: true,
            helpText: 'Find this in your Glassdoor company profile URL'
          }
        ];

      case 'outlook-calendar':
        return [
          {
            field: 'info',
            label: 'Outlook Calendar Requirements',
            type: 'info',
            required: false,
            helpText: 'Uses your Microsoft 365 account for calendar access and Teams meeting integration.'
          }
        ];

      case 'twitter':
        return [
          {
            field: 'info',
            label: 'Twitter/X Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to Twitter/X to authorize posting to your company account.'
          },
          {
            field: 'handle',
            label: 'Company Twitter Handle',
            type: 'text',
            placeholder: '@yourcompany',
            required: true,
            helpText: 'Your company Twitter/X username'
          }
        ];

      case 'facebook':
        return [
          {
            field: 'info',
            label: 'Facebook Requirements',
            type: 'info',
            required: false,
            helpText: 'You will be redirected to Facebook to authorize posting to your company page.'
          }
        ];

      case 'google-analytics':
        return [
          {
            field: 'trackingId',
            label: 'Google Analytics Tracking ID',
            type: 'text',
            placeholder: 'G-XXXXXXXXXX or UA-XXXXXXXXX',
            required: true,
            helpText: 'Find this in your Google Analytics admin panel'
          },
          {
            field: 'info',
            label: 'Setup Instructions',
            type: 'info',
            required: false,
            helpText: '1. Go to Google Analytics\n2. Admin → Data Streams\n3. Copy your Measurement ID (G-XXXXXXXXXX)'
          }
        ];

      default:
        return [];
    }
  }

  get isOAuthProvider(): boolean {
    return ['linkedin', 'gmail', 'outlook', 'google-calendar', 'outlook-calendar', 'twitter', 'facebook'].includes(this.integration?.id || '');
  }

  get isApiKeyProvider(): boolean {
    return ['indeed', 'glassdoor', 'google-analytics'].includes(this.integration?.id || '');
  }

  onDismiss(): void {
    this.dismiss.emit();
  }

  onConnect(): void {
    // Validate required fields
    const hasAllRequired = this.requirements
      .filter(r => r.required && r.type !== 'info')
      .every(r => this.formData[r.field]?.trim());

    if (!hasAllRequired) {
      alert('Please fill in all required fields');
      return;
    }

    this.connect.emit();
  }
}
