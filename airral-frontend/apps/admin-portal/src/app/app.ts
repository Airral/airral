import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { AuthService } from '@airral/shared-auth';

@Component({
  imports: [CommonModule, RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected title = 'admin-portal';
  private readonly auth = inject(AuthService);
  /** One nav for every admin page, instead of each page linking to the others. */
  protected readonly signedIn = toSignal(this.auth.currentUser$.pipe(map((user) => !!user)), {
    initialValue: this.auth.isAuthenticated(),
  });
}
