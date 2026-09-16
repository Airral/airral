import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { CandidatePortalService } from '@airral/shared-api';
import { catchError, of } from 'rxjs';

/**
 * The page every AIRRAL email footer links to.
 *
 * <p>It did not exist. The footer built
 * {@code https://apply.airral.com/unsubscribe?token=...}, the router's
 * catch-all redirected that to /jobs, and the token was dropped -- so the
 * unsubscribe control in every email did nothing and said nothing. Opening the
 * API's own path to the public did not fix it, because no email has ever
 * contained that address.
 *
 * <p>Loading this page changes nothing on purpose. Mail clients and security
 * scanners prefetch links, so acting on the GET would unsubscribe people who
 * never clicked. The work happens on the button, which POSTs.
 */
@Component({
  selector: 'app-unsubscribe',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './unsubscribe.component.html',
  styleUrls: ['./unsubscribe.component.css'],
})
export class UnsubscribeComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly candidateApi = inject(CandidatePortalService);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);

  token = '';
  working = false;
  /** null until the button has been pressed, so nothing is claimed before then. */
  outcome: { unsubscribed: boolean; message: string } | null = null;

  ngOnInit(): void {
    this.token = this.route.snapshot.queryParamMap.get('token') ?? '';
    this.changeDetectorRef.markForCheck();
  }

  confirm(): void {
    if (!this.token || this.working) return;
    this.working = true;
    this.changeDetectorRef.markForCheck();

    this.candidateApi
      .unsubscribeAll(this.token)
      .pipe(
        catchError(() =>
          of({
            unsubscribed: false,
            message: 'We could not reach the server. Nothing has changed — please try again.',
          })
        )
      )
      .subscribe((result) => {
        this.outcome = result;
        this.working = false;
        // Zoneless: nothing repaints off an HTTP callback on its own.
        this.changeDetectorRef.markForCheck();
      });
  }
}
