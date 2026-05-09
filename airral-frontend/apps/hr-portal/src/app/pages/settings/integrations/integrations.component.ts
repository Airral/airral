import { CommonModule } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { IntegrationDialogComponent } from './integration-dialog/integration-dialog.component';

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

@Component({
  selector: 'app-integrations',
  standalone: true,
  imports: [CommonModule, IntegrationDialogComponent],
  templateUrl: './integrations.component.html',
  styleUrl: './integrations.component.css',
})
export class IntegrationsComponent implements OnInit {
  selectedIntegration: Integration | null = null;
  showAllIntegrations = false;

  integrations: Integration[] = [
    // Job Boards
    {
      id: 'linkedin',
      name: 'LinkedIn',
      description: 'Post jobs automatically to your company LinkedIn page',
      icon: '💼', // Changed to emoji
      connected: true,
      connectedAt: '2026-04-20',
      connectedEmail: 'hr@company.com',
      category: 'job-boards',
      features: [
        'Auto-post jobs to company page',
        'Track LinkedIn applications',
        'Sync company profile'
      ],
      tier: 'all'
    },
    {
      id: 'indeed',
      name: 'Indeed',
      description: 'Automatically post open positions to Indeed job board',
      icon: '🔍', // Changed to emoji
      connected: false,
      category: 'job-boards',
      features: [
        'Post to Indeed job board',
        'Sponsored job options',
        'Track Indeed applications'
      ],
      tier: 'all'
    },
    {
      id: 'gmail',
      name: 'Gmail',
      description: 'Send candidate emails and notifications through Gmail',
      icon: '📧', // Changed to emoji
      connected: false,
      category: 'email',
      features: [
        'Send emails from your Gmail',
        'Track email opens',
        'Use Gmail templates',
        'Schedule emails'
      ],
      tier: 'all'
    },
    {
      id: 'outlook',
      name: 'Microsoft Outlook',
      description: 'Connect Outlook for email communications',
      icon: '📨', // Changed to emoji
      connected: false,
      category: 'email',
      features: [
        'Send emails via Outlook',
        'Sync calendar events',
        'Email tracking',
        'Templates'
      ],
      tier: 'all'
    },
    {
      id: 'google-calendar',
      name: 'Google Calendar',
      description: 'Schedule interviews and sync events with Google Calendar',
      icon: '📅', // Changed to emoji
      connected: false,
      category: 'calendar',
      features: [
        'Schedule interviews',
        'Auto-send calendar invites',
        'Check availability',
        'Video meeting links'
      ],
      tier: 'all'
    },
    {
      id: 'outlook-calendar',
      name: 'Outlook Calendar',
      description: 'Sync interview schedules with Outlook Calendar',
      icon: '🗓️', // Changed to emoji
      connected: false,
      category: 'calendar',
      features: [
        'Schedule interviews',
        'Teams meeting integration',
        'Availability checking',
        'Send calendar invites'
      ],
      tier: 'all'
    },
    {
      id: 'glassdoor',
      name: 'Glassdoor',
      description: 'Share job openings on Glassdoor',
      icon: '🏢', // Changed to emoji
      connected: false,
      category: 'job-boards',
      features: [
        'Post jobs to Glassdoor',
        'Showcase company reviews',
        'Track applications'
      ],
      tier: 'professional'
    },
    {
      id: 'twitter',
      name: 'Twitter/X',
      description: 'Share job openings on your company Twitter account',
      icon: '🐦', // Changed to emoji
      connected: false,
      category: 'social',
      features: [
        'Auto-tweet job posts',
        'Hashtag optimization',
        'Track engagement'
      ],
      tier: 'professional'
    },
    {
      id: 'facebook',
      name: 'Facebook',
      description: 'Post jobs to your company Facebook page',
      icon: '📘', // Changed to emoji
      connected: false,
      category: 'social',
      features: [
        'Post to Facebook page',
        'Facebook Jobs integration',
        'Track applications'
      ],
      tier: 'all'
    },
    {
      id: 'google-analytics',
      name: 'Google Analytics',
      description: 'Track job page views and application sources',
      icon: '📊', // Changed to emoji
      connected: false,
      category: 'analytics',
      features: [
        'Track job views',
        'Application source tracking',
        'Conversion analytics',
        'Custom reports'
      ],
      tier: 'professional'
    }
  ];

  categories = [
    { id: 'job-boards', name: 'Job Boards', icon: '💼' },
    { id: 'email', name: 'Email', icon: '📧' },
    { id: 'calendar', name: 'Calendar', icon: '📅' },
    { id: 'social', name: 'Social', icon: '📱' },
    { id: 'analytics', name: 'Analytics', icon: '📊' }
  ];

  selectedCategory: string | null = null;

  ngOnInit(): void {
    // Load integration status from API
    this.loadIntegrationStatus();
  }

  loadIntegrationStatus(): void {
    // TODO: Call API to get real connection status
    console.log('Loading integration status...');
  }

  filterByCategory(categoryId: string | null): void {
    this.selectedCategory = categoryId;
    this.showAllIntegrations = false; // Reset when filtering
  }

  get filteredIntegrations(): Integration[] {
    if (!this.selectedCategory) {
      return this.integrations;
    }
    return this.integrations.filter(i => i.category === this.selectedCategory);
  }

  get displayedIntegrations(): Integration[] {
    const filtered = this.filteredIntegrations;
    // Show only 6 cards initially to reduce scrolling
    return this.showAllIntegrations ? filtered : filtered.slice(0, 6);
  }

  get hasMoreIntegrations(): boolean {
    return this.filteredIntegrations.length > 6 && !this.showAllIntegrations;
  }

  openConnectionDialog(integration: Integration): void {
    this.selectedIntegration = integration;
  }

  closeDialog(): void {
    this.selectedIntegration = null;
  }

  async handleConnect(): Promise<void> {
    if (!this.selectedIntegration) return;

    const integration = this.selectedIntegration;
    console.log('Connecting:', integration.id);

    // Close dialog
    this.closeDialog();

    // Handle connection based on type
    switch (integration.id) {
      case 'linkedin':
        await this.connectLinkedIn();
        break;
      case 'gmail':
        await this.connectGmail();
        break;
      case 'outlook':
        await this.connectOutlook();
        break;
      case 'google-calendar':
        await this.connectGoogleCalendar();
        break;
      case 'indeed':
        // API key already captured in dialog
        alert('Indeed integration coming soon!');
        break;
      default:
        alert(`${integration.name} integration coming soon!`);
    }
  }

  disconnectIntegration(integration: Integration): void {
    if (confirm(`Disconnect ${integration.name}? Jobs will no longer post to this platform.`)) {
      // TODO: Call disconnect API
      integration.connected = false;
      integration.connectedAt = undefined;
      integration.connectedEmail = undefined;
    }
  }

  // Integration-specific OAuth flows
  async connectLinkedIn(): Promise<void> {
    try {
      const response = await fetch('/api/integrations/linkedin/authorize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ organizationId: 1 })
      });
      const { authorizationUrl } = await response.json();
      window.location.href = authorizationUrl;
    } catch (error) {
      alert('Failed to connect LinkedIn. Please try again.');
    }
  }

  async connectGmail(): Promise<void> {
    // Google OAuth 2.0 for Gmail
    const clientId = 'YOUR_GOOGLE_CLIENT_ID';
    const redirectUri = encodeURIComponent(window.location.origin + '/auth/google/callback');
    const scope = encodeURIComponent('https://www.googleapis.com/auth/gmail.send');

    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?` +
      `client_id=${clientId}&` +
      `redirect_uri=${redirectUri}&` +
      `response_type=code&` +
      `scope=${scope}&` +
      `access_type=offline`;

    window.location.href = authUrl;
  }

  async connectOutlook(): Promise<void> {
    // Microsoft OAuth 2.0 for Outlook
    const clientId = 'YOUR_MICROSOFT_CLIENT_ID';
    const redirectUri = encodeURIComponent(window.location.origin + '/auth/microsoft/callback');
    const scope = encodeURIComponent('Mail.Send Calendars.ReadWrite');

    const authUrl = `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?` +
      `client_id=${clientId}&` +
      `redirect_uri=${redirectUri}&` +
      `response_type=code&` +
      `scope=${scope}`;

    window.location.href = authUrl;
  }

  async connectGoogleCalendar(): Promise<void> {
    // Google OAuth 2.0 for Calendar
    const clientId = 'YOUR_GOOGLE_CLIENT_ID';
    const redirectUri = encodeURIComponent(window.location.origin + '/auth/google/callback');
    const scope = encodeURIComponent('https://www.googleapis.com/auth/calendar');

    const authUrl = `https://accounts.google.com/o/oauth2/v2/auth?` +
      `client_id=${clientId}&` +
      `redirect_uri=${redirectUri}&` +
      `response_type=code&` +
      `scope=${scope}&` +
      `access_type=offline`;

    window.location.href = authUrl;
  }
}
