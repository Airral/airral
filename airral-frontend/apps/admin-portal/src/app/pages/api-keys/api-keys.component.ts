import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ApiKeyService,
  ApiKeySummary,
  IssuedApiKey,
} from '../../services/api-key.service';

/**
 * Issue, review and revoke the API keys that let someone's AI agent reach
 * AIRRAL through MCP.
 *
 * <p>Keys are looked up per user rather than listed globally. Deliberate: the
 * questions this screen answers are all about one person — has their key ever
 * been used, is it still live, do they need a new one — and a global list of
 * every key in the system invites scanning through credentials belonging to
 * people you have no business looking at.
 */
@Component({
  selector: 'app-api-keys',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './api-keys.component.html',
  styleUrls: ['./api-keys.component.css'],
})
export class ApiKeysComponent {
  private readonly service = inject(ApiKeyService);

  /** The user whose keys are on screen. */
  email = '';
  searchedEmail = signal('');

  readonly keys = signal<ApiKeySummary[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');

  // ── issuing ──
  showIssueForm = false;
  newName = '';
  newExpiryDays = 30;
  readonly issuing = signal(false);

  /**
   * Held only until the admin dismisses it. There is no way to recover it
   * afterwards, which is the point, so the UI has to be emphatic about copying
   * it now.
   */
  readonly justIssued = signal<IssuedApiKey | null>(null);
  readonly copied = signal<string>('');

  search(): void {
    const target = this.email.trim();
    if (!target) {
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.justIssued.set(null);

    this.service.listFor(target).subscribe({
      next: (keys) => {
        this.keys.set(keys);
        this.searchedEmail.set(target);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(
          `Could not load keys for ${target}. Check the address, or that you are still signed in.`
        );
        this.loading.set(false);
      },
    });
  }

  issue(): void {
    const target = this.searchedEmail() || this.email.trim();
    if (!target || !this.newName.trim() || this.issuing()) {
      return;
    }

    this.issuing.set(true);
    this.error.set('');

    this.service
      .issue({
        email: target,
        name: this.newName.trim(),
        // 0 means never, which the backend reads as no expiry.
        expiresInDays: this.newExpiryDays > 0 ? this.newExpiryDays : undefined,
      })
      .subscribe({
        next: (issued) => {
          this.justIssued.set(issued);
          this.newName = '';
          this.showIssueForm = false;
          this.issuing.set(false);
          // Refresh so the new key appears in the list behind the banner.
          this.service.listFor(target).subscribe((keys) => this.keys.set(keys));
        },
        error: (err) => {
          this.error.set(
            err?.error?.message ??
              'Could not issue the key. The account may not exist, or may be inactive.'
          );
          this.issuing.set(false);
        },
      });
  }

  revoke(key: ApiKeySummary): void {
    // Irreversible, and the person's agent stops working the moment it lands.
    const ok = window.confirm(
      `Revoke "${key.name}"?\n\nThis takes effect immediately and cannot be undone. ` +
        `Whoever is using it will need a new key.`
    );
    if (!ok) {
      return;
    }

    this.service.revoke(key.keyId, 'revoked from admin portal').subscribe({
      next: () => this.search(),
      error: () => this.error.set(`Could not revoke ${key.keyId}.`),
    });
  }

  copy(value: string, label: string): void {
    navigator.clipboard?.writeText(value).then(
      () => {
        this.copied.set(label);
        window.setTimeout(() => this.copied.set(''), 2000);
      },
      () => this.error.set('Could not copy. Select the text and copy manually.')
    );
  }

  dismissIssued(): void {
    this.justIssued.set(null);
  }

  /** "never" reads better than an empty cell for a key that has sat unused. */
  lastUsedLabel(key: ApiKeySummary): string {
    return key.lastUsedAt ? this.shortDate(key.lastUsedAt) : 'never used';
  }

  expiryLabel(key: ApiKeySummary): string {
    if (!key.expiresAt) {
      return 'no expiry';
    }
    const expires = new Date(key.expiresAt);
    const days = Math.ceil((expires.getTime() - Date.now()) / 86_400_000);
    if (days < 0) {
      return 'expired';
    }
    if (days === 0) {
      return 'expires today';
    }
    return `${days} day${days === 1 ? '' : 's'} left`;
  }

  isExpiringSoon(key: ApiKeySummary): boolean {
    if (!key.expiresAt || !key.active) {
      return false;
    }
    const days = (new Date(key.expiresAt).getTime() - Date.now()) / 86_400_000;
    return days <= 7;
  }

  shortDate(value: string): string {
    return new Date(value).toLocaleDateString(undefined, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  get activeCount(): number {
    return this.keys().filter((k) => k.active).length;
  }
}
