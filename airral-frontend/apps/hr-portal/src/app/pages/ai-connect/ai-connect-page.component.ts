import { Component } from '@angular/core';
import { AiConnectComponent } from '@airral/shared-ui';

/** Connect an AI assistant to AIRRAL: the company portal's page around the shared component. */
@Component({
  selector: 'app-ai-connect-page',
  standalone: true,
  imports: [AiConnectComponent],
  template: `
    <section class="settings-container">
      <header class="settings-header">
        <h1>Connect an AI assistant</h1>
        <p>Use AIRRAL from Claude or another AI app, with a key only you hold.</p>
      </header>
      <airral-ai-connect audience="employer" />
    </section>
  `,
  styleUrl: '../settings/settings-page.css',
})
export class AiConnectPageComponent {}
