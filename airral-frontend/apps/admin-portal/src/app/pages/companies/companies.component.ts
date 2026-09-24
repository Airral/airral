import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { CompanyForReview, CompanyReviewService, CompanyReviewStatus } from '../../services/company-review.service';

/**
 * Employers waiting for a human decision.
 *
 * <p>A company is verified automatically when one of its people proves an
 * address on the company's own domain. Everything else -- free-mail sign-ups,
 * companies with no domain, a second company claiming a domain already held --
 * lands here, and its open jobs stay out of the candidate catalogue until an
 * admin approves it.
 */
@Component({
  selector: 'app-companies',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './companies.component.html',
  styleUrls: ['./companies.component.css'],
})
export class CompaniesComponent implements OnInit {
  private readonly service = inject(CompanyReviewService);

  readonly status = signal<CompanyReviewStatus>('PENDING');
  readonly companies = signal<CompanyForReview[]>([]);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly busyId = signal<number | null>(null);
  notes: Record<number, string> = {};

  ngOnInit(): void {
    this.load();
  }

  show(status: CompanyReviewStatus): void {
    this.status.set(status);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.service.list(this.status()).subscribe({
      next: (res) => {
        this.companies.set(res.companies ?? []);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.status === 403 ? 'Only platform admins can review companies.' : 'Could not load companies.');
        this.loading.set(false);
      },
    });
  }

  decide(company: CompanyForReview, decision: 'approve' | 'reject'): void {
    if (this.busyId() !== null) return;
    if (decision === 'reject' && !confirm(`Reject ${company.name}? Its jobs will be hidden from candidates.`)) return;
    this.busyId.set(company.id);
    const note = (this.notes[company.id] ?? '').trim();
    const call = decision === 'approve' ? this.service.approve(company.id, note) : this.service.reject(company.id, note);
    call.subscribe({
      next: () => {
        this.busyId.set(null);
        this.companies.update((list) => list.filter((c) => c.id !== company.id));
      },
      error: () => {
        this.busyId.set(null);
        this.error.set(`Could not ${decision} ${company.name}. Try again.`);
      },
    });
  }
}
