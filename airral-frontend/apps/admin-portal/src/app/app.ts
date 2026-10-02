import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { AuthService } from '@airral/shared-auth';
import { ApiClientService } from '@airral/shared-api';

import { AirralLogoComponent } from '@airral/shared-ui';
@Component({
  imports: [CommonModule, RouterModule, AirralLogoComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected title = 'admin-portal';
  private readonly auth = inject(AuthService);
  private readonly api = inject(ApiClientService);
  /** One nav for every admin page, instead of each page linking to the others. */
  protected readonly signedIn = toSignal(this.auth.currentUser$.pipe(map((user) => !!user)), {
    initialValue: this.auth.isAuthenticated(),
  });
  /** Who is signed in, so a shared screen says whose admin session it is. */
  protected readonly signedInEmail = toSignal(this.auth.currentUser$.pipe(map((user) => user?.email ?? '')), {
    initialValue: this.auth.getCurrentUser()?.email ?? '',
  });
  protected readonly signingOutEverywhere = signal(false);
  protected readonly signOutError = signal('');

  /**
   * Ends this browser's session.
   *
   * <p>The admin portal had no way to sign out: a session lasted its full 24
   * hours on whatever computer it was opened on. Reloading the root afterwards
   * lets authGuard send the person to the right login page, rather than this
   * component knowing which portal hosts it.
   */
  protected signOut(): void {
    this.auth.logout();
    window.location.href = '/';
  }

  /**
   * Ends every session for this account, on every device, including this one.
   *
   * <p>The ordinary sign-out only drops this browser's token; a copy left open
   * on another machine keeps working until it expires. This asks the server to
   * reject all of them (POST /api/auth/revoke-sessions), which is the one that
   * helps after a laptop goes missing.
   */
  protected signOutEverywhere(): void {
    if (this.signingOutEverywhere()) {
      return;
    }
    if (!window.confirm('Sign out of the admin portal on every device, including this one?')) {
      return;
    }
    this.signingOutEverywhere.set(true);
    this.signOutError.set('');
    this.api.post<{ revoked: boolean }>('/auth/revoke-sessions', {}).subscribe({
      next: () => this.signOut(),
      error: () => {
        this.signingOutEverywhere.set(false);
        this.signOutError.set('Could not sign out other devices. Try again, or sign out here.');
      },
    });
  }
}
