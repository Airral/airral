import { CommonModule } from '@angular/common';
import { Component, Input, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiAccessApiService, AiAccessKey, AiAccessOverview, IssuedAiAccessKey } from '@airral/shared-api';

type SetupTab = 'claude-code' | 'claude-desktop' | 'other';

/** What a scope lets an assistant do, in the words the page uses. */
const SCOPE_LABELS: Record<string, string> = {
  'jobs:read': 'Search and read AIRRAL jobs',
  'pipeline:read': "See your company's jobs and how hiring is going, in numbers",
};

const PLACEHOLDER_KEY = 'YOUR_AIRRAL_KEY';

/**
 * The server's own sentence for a refusal (a 4xx it wrote for people),
 * otherwise the fallback. Angular's "Http failure response for ..." text and
 * 5xx details are never shown.
 */
function friendly(failure: unknown, fallback: string): string {
  const error = failure as { status?: number; message?: string } | null;
  const message = error?.message;
  if (error?.status && error.status >= 400 && error.status < 500 && error.status !== 404
      && message && !message.startsWith('Http failure')) {
    return message;
  }
  return fallback;
}

/**
 * Connect an AI assistant to AIRRAL with a personal key: make one, see how to
 * add it to Claude or another app, and revoke it. Shared by the applicant and
 * HR portals, which each wrap it in a page.
 *
 * The raw key lives only in this component's memory, is shown once, and is
 * cleared when the person says they saved it or leaves the page. It never goes
 * in the address, the title, storage or the console.
 */
@Component({
  selector: 'airral-ai-connect',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './ai-connect.component.html',
  styleUrls: ['./ai-connect.component.css'],
})
export class AiConnectComponent implements OnInit, OnDestroy {
  /** Which portal this is on; the examples also follow what a key can do. */
  @Input() audience: 'applicant' | 'employer' = 'applicant';

  private readonly api = inject(AiAccessApiService);

  readonly loading = signal(true);
  /** The first load failed: nothing to show yet. */
  readonly loadError = signal('');
  /** A later refresh failed: keep the page, and any new key, on screen. */
  readonly refreshError = signal('');
  readonly overview = signal<AiAccessOverview | null>(null);
  readonly issued = signal<IssuedAiAccessKey | null>(null);
  readonly name = signal('');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly confirmingRevoke = signal<string | null>(null);
  readonly copied = signal('');
  readonly tab = signal<SetupTab>('claude-code');

  readonly activeKeys = computed(() => (this.overview()?.keys ?? []).filter((key) => !key.expired));
  readonly atLimit = computed(() => this.activeKeys().length >= (this.overview()?.maxKeys ?? 3));
  readonly mcpUrl = computed(() => this.overview()?.mcpUrl ?? 'https://mcp.airral.com/mcp');
  /** The real key right after it was made, a placeholder otherwise. */
  readonly keyForSetup = computed(() => this.issued()?.key ?? PLACEHOLDER_KEY);
  readonly seesCompanyJobs = computed(() => (this.overview()?.selfServiceScopes ?? []).includes('pipeline:read'));

  // User scope, so AIRRAL is there in every folder, not only the one the
  // command was run in.
  readonly claudeCodeCommand = computed(
    () =>
      `claude mcp add --transport http --scope user airral ${this.mcpUrl()} --header "Authorization: Bearer ${this.keyForSetup()}"`,
  );
  readonly claudeCodeReplace = 'claude mcp remove --scope user airral';
  // Only the entry: it goes inside the file's "mcpServers", next to whatever is
  // already there, so nobody pastes a second top-level object and breaks it.
  readonly claudeDesktopEntry = computed(
    () =>
      `"airral": ${JSON.stringify(
        {
          command: 'npx',
          args: ['-y', 'mcp-remote', this.mcpUrl(), '--header', 'Authorization:${AIRRAL_AUTH}'],
          env: { AIRRAL_AUTH: `Bearer ${this.keyForSetup()}` },
        },
        null,
        2,
      )}`,
  );
  readonly cursorConfig = computed(() =>
    JSON.stringify(
      { mcpServers: { airral: { url: this.mcpUrl(), headers: { Authorization: `Bearer ${this.keyForSetup()}` } } } },
      null,
      2,
    ),
  );
  readonly vsCodeConfig = computed(() =>
    JSON.stringify(
      {
        servers: {
          airral: { type: 'http', url: this.mcpUrl(), headers: { Authorization: `Bearer ${this.keyForSetup()}` } },
        },
      },
      null,
      2,
    ),
  );

  readonly examples = computed(() => {
    if (this.seesCompanyJobs()) {
      return ['Which of our jobs have new applicants waiting?', 'How is hiring going on our open jobs?'];
    }
    return this.audience === 'employer'
      ? ['What warehouse and logistics roles are open in Chicago right now?', 'Find operations manager jobs that list a salary.']
      : ['Find warehouse lead jobs in Austin that pay over $25 an hour.', 'Show me remote product design roles posted this week.'];
  });

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    // The only copy of a new key goes with the page.
    this.issued.set(null);
  }

  load(): void {
    this.loading.set(true);
    this.api.overview().subscribe({
      next: (overview) => {
        this.overview.set(overview);
        this.loadError.set('');
        this.refreshError.set('');
        this.loading.set(false);
      },
      error: (failure: unknown) => {
        const message = friendly(failure, 'Could not load your AI assistant keys. Try again in a moment.');
        if (this.overview()) {
          this.refreshError.set(message);
        } else {
          this.loadError.set(message);
        }
        this.loading.set(false);
      },
    });
  }

  create(): void {
    const name = this.name().trim();
    if (!name || this.busy()) {
      this.error.set(name ? '' : 'Give the key a name, like "My laptop", so you can tell it apart later.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.api.create(name).subscribe({
      next: (issued) => {
        this.issued.set(issued);
        this.name.set('');
        this.busy.set(false);
        this.load();
      },
      error: (failure: unknown) => {
        this.error.set(friendly(failure, 'Could not make a key. Try again in a moment.'));
        this.busy.set(false);
      },
    });
  }

  saved(): void {
    this.issued.set(null);
    this.copied.set('');
    this.error.set('');
  }

  revoke(key: AiAccessKey): void {
    if (this.confirmingRevoke() !== key.keyId) {
      this.confirmingRevoke.set(key.keyId);
      return;
    }
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    this.api.revoke(key.keyId).subscribe({
      next: () => {
        this.confirmingRevoke.set(null);
        this.busy.set(false);
        if (this.issued()?.keyId === key.keyId) {
          this.issued.set(null);
        }
        this.notice.set(`Revoked "${key.name}". Anything using it stops working now.`);
        this.load();
      },
      error: (failure: unknown) => {
        this.confirmingRevoke.set(null);
        this.busy.set(false);
        this.error.set(friendly(failure, 'Could not revoke that key. Try again in a moment.'));
      },
    });
  }

  async copy(text: string, what: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.error.set('');
      this.copied.set(what);
      setTimeout(() => this.copied() === what && this.copied.set(''), 2500);
    } catch {
      this.error.set('Copying is not available in this browser. Select the text and copy it instead.');
    }
  }

  scopeLabels(scopes: string[]): string {
    return scopes.map((scope) => SCOPE_LABELS[scope] ?? scope).join(' · ');
  }
}
