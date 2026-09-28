import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ApplicationApiService } from '@airral/shared-api';
import { Application, Offer, OfferStatus, CreateOfferRequest } from '@airral/shared-types';
import { forkJoin } from 'rxjs';

@Component({
  selector: 'app-offers',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule],
  templateUrl: './offers.component.html',
  styleUrls: ['./offers.component.css']
})
export class OffersComponent implements OnInit {
  offers: Offer[] = [];
  applications: Application[] = [];
  loading = false;
  error: string | null = null;
  notice: string | null = null;

  showCreateForm = false;
  showOfferDetail = false;
  selectedOffer: Offer | null = null;
  selectedApplication: Application | null = null;

  filterStatus: OfferStatus | 'ALL' = 'ALL';
  OfferStatus = OfferStatus;

  offerForm: FormGroup;
  private applicationApiService = inject(ApplicationApiService);
  private formBuilder = inject(FormBuilder);

  constructor() {
    this.offerForm = this.formBuilder.group({
      applicationId: ['', Validators.required],
      salary: ['', [Validators.required, Validators.min(0)]],
      currency: ['USD', Validators.required],
      startDate: ['', Validators.required],
      offerLetter: ['We are pleased to offer you this position...', Validators.required],
      benefits: ['', Validators.required],
      contingencies: ['']
    });
  }

  ngOnInit() {
    this.loadData();
  }

  onFilterStatusChange(status: string) {
    this.filterStatus = (status === 'ALL' ? 'ALL' : status) as (OfferStatus | 'ALL');
  }

  loadData() {
    this.loading = true;
    this.error = null;

    forkJoin({
      applications: this.applicationApiService.getAllApplications(),
      offers: this.applicationApiService.getAllOffers(),
    }).subscribe({
      next: ({ applications, offers }) => {
        this.applications = applications;
        this.offers = offers;
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load offers data';
        this.loading = false;
      },
    });
  }

  openCreateForm() {
    this.showCreateForm = true;
    this.selectedOffer = null;
  }

  cancelForm() {
    this.showCreateForm = false;
    this.offerForm.reset({ currency: 'USD', offerLetter: 'We are pleased to offer you this position...' });
  }

  submitOffer() {
    if (!this.offerForm.valid) {
      this.error = 'Please fill in all required fields';
      return;
    }

    const formValue = this.offerForm.value;
    const request: CreateOfferRequest = {
      applicationId: parseInt(formValue.applicationId),
      jobId: this.applications.find(a => a.id === parseInt(formValue.applicationId))?.jobId ?? 0,
      salary: parseFloat(formValue.salary),
      currency: formValue.currency,
      startDate: formValue.startDate,
      offerLetter: formValue.offerLetter,
      benefits: formValue.benefits,
      contingencies: formValue.contingencies
    };

    this.applicationApiService.createOffer(request).subscribe({
      next: (offer) => {
        this.offers.push(offer);
        this.showCreateForm = false;
        this.offerForm.reset({ currency: 'USD', offerLetter: 'We are pleased to offer you this position...' });
        this.error = null;
      },
      error: (err: Error) => {
        this.error = err?.message || 'The offer could not be saved.';
      }
    });
  }

  viewOffer(offer: Offer) {
    this.selectedOffer = offer;
    this.showOfferDetail = true;
  }

  closeOfferDetail() {
    this.showOfferDetail = false;
    this.selectedOffer = null;
  }

  sendOffer(offer: Offer) {
    this.applicationApiService.sendOffer({ offerId: offer.id, expiresInDays: 14 }).subscribe({
      next: (updatedOffer) => {
        this.replaceOffer(updatedOffer);
        this.error = null;
        this.notice = updatedOffer.candidateHasAccount
          ? `Sent. ${updatedOffer.candidateName || 'The candidate'} answers on AIRRAL within 14 days.`
          : `Sent. ${updatedOffer.candidateName || 'The candidate'} has no AIRRAL account, so record their answer here when you have it.`;
      },
      error: (err: Error) => {
        this.error = err?.message || 'The offer could not be sent.';
      }
    });
  }

  /** For a candidate HR added by hand: they have no account, so HR records their answer. */
  recordAnswer(offer: Offer, accepted: boolean) {
    const name = offer.candidateName || 'the candidate';
    if (!confirm(accepted ? `Record that ${name} accepted? They will be marked hired.` : `Record that ${name} declined?`)) return;
    const answer$ = accepted
      ? this.applicationApiService.acceptOffer(offer.id)
      : this.applicationApiService.declineOffer(offer.id);
    answer$.subscribe({
      next: (updatedOffer) => {
        this.replaceOffer(updatedOffer);
        this.error = null;
        this.notice = accepted ? `${name} is marked hired.` : `Recorded that ${name} declined.`;
      },
      error: (err: Error) => {
        this.error = err?.message || 'The answer could not be recorded.';
      }
    });
  }

  canRecordAnswer(offer: Offer): boolean {
    return offer.status === OfferStatus.SENT && offer.candidateHasAccount === false;
  }

  awaitingCandidate(offer: Offer): boolean {
    return offer.status === OfferStatus.SENT && offer.candidateHasAccount === true;
  }

  private replaceOffer(updated: Offer) {
    const index = this.offers.findIndex(o => o.id === updated.id);
    if (index >= 0) this.offers[index] = updated;
    if (this.selectedOffer?.id === updated.id) this.selectedOffer = updated;
  }

  withdrawOffer(offer: Offer) {
    if (!confirm('Are you sure you want to withdraw this offer?')) return;

    this.applicationApiService.withdrawOffer(offer.id).subscribe({
      next: (updatedOffer) => {
        this.replaceOffer(updatedOffer);
        this.error = null;
        this.notice = 'Offer withdrawn.';
      },
      error: (err: Error) => {
        this.error = err?.message || 'The offer could not be withdrawn.';
      }
    });
  }

  getFilteredOffers(): Offer[] {
    return this.filterStatus === 'ALL'
      ? this.offers
      : this.offers.filter(o => o.status === this.filterStatus);
  }

  getStatusBadgeClass(status: OfferStatus): string {
    const classes: { [key in OfferStatus]: string } = {
      [OfferStatus.DRAFT]: 'status-draft',
      [OfferStatus.SENT]: 'status-sent',
      [OfferStatus.ACCEPTED]: 'status-accepted',
      [OfferStatus.DECLINED]: 'status-declined',
      [OfferStatus.EXPIRED]: 'status-expired',
      [OfferStatus.WITHDRAWN]: 'status-withdrawn'
    };
    return classes[status] || '';
  }

  getApplicationName(applicationId: number): string {
    return this.applications.find(a => a.id === applicationId)?.applicantEmail ?? 'Unknown';
  }

  formatCurrency(value: number, currency: string): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(value);
  }

  formatDate(dateString?: string): string {
    if (!dateString) return '--';
    return new Date(dateString).toLocaleDateString();
  }

  isOfferExpired(offer: Offer): boolean {
    if (!offer.expiresAt) return false;
    return new Date(offer.expiresAt) < new Date();
  }

  canSendOffer(offer: Offer): boolean {
    return offer.status === OfferStatus.DRAFT;
  }

  canWithdraw(offer: Offer): boolean {
    const draftStatus = OfferStatus.DRAFT as string;
    const sentStatus = OfferStatus.SENT as string;
    return offer.status === draftStatus || offer.status === sentStatus;
  }

  getStatusCount(status: OfferStatus | string): number {
    return this.offers.filter(o => o.status === status).length;
  }
}
