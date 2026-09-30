import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiError, ApplicationApiService } from '@airral/shared-api';
import { Application, ApplicationStatus, Offer, OfferStatus, CreateOfferRequest } from '@airral/shared-types';
import { Observable, finalize, forkJoin } from 'rxjs';

/** Applications that no longer take an offer. */
const CLOSED: string[] = [ApplicationStatus.HIRED, ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN];

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
  /** An offer step is on its way to the server: its buttons wait, so a second click cannot repeat it. */
  busy = false;
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
  private route = inject(ActivatedRoute);
  /** The candidate the Candidates page sent us here for, once. */
  private requestedApplicationId: number | null = null;

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
    const requested = Number(this.route.snapshot.queryParamMap.get('applicationId'));
    this.requestedApplicationId = Number.isInteger(requested) && requested > 0 ? requested : null;
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
        this.openRequestedApplication();
      },
      error: () => {
        this.error = 'Failed to load offers data';
        this.loading = false;
      },
    });
  }

  /**
   * From "Make an offer" or "See the offer" on the Candidates page: the
   * candidate's offer still being made, else a new one for them, else their
   * latest.
   */
  private openRequestedApplication() {
    const applicationId = this.requestedApplicationId;
    if (applicationId === null) return;
    this.requestedApplicationId = null;
    const theirs = this.offers
      .filter((offer) => offer.applicationId === applicationId)
      .sort((a, b) => b.id - a.id);
    const open = theirs.find((offer) => this.isOpen(offer));
    if (open) {
      this.viewOffer(open);
    } else if (this.offerableApplications.some((application) => application.id === applicationId)) {
      this.openCreateForm();
      this.offerForm.patchValue({ applicationId: String(applicationId) });
    } else if (theirs[0]) {
      this.viewOffer(theirs[0]);
    }
  }

  /** A draft, or a sent offer still waiting for an answer. */
  private isOpen(offer: Offer): boolean {
    return offer.status === OfferStatus.DRAFT || offer.status === OfferStatus.SENT;
  }

  /** Candidates who can be made an offer: still in the running, with none already being made. */
  get offerableApplications(): Application[] {
    return this.applications.filter(
      (application) =>
        !CLOSED.includes(application.status) &&
        !this.offers.some((offer) => offer.applicationId === application.id && this.isOpen(offer)),
    );
  }

  openCreateForm() {
    this.showCreateForm = true;
    this.selectedOffer = null;
  }

  /**
   * One offer step at a time. A conflict means the offer changed elsewhere
   * (withdrawn in another tab, answered by the candidate), so the list is read
   * again to show where it stands.
   */
  private step(request: Observable<Offer>, done: (offer: Offer) => void, failed: string) {
    if (this.busy) return;
    this.busy = true;
    this.error = null;
    this.notice = null;
    request.pipe(finalize(() => (this.busy = false))).subscribe({
      next: done,
      error: (err: ApiError) => {
        this.error = err?.message || failed;
        if (err?.status === 409) this.loadData();
      },
    });
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

    this.step(this.applicationApiService.createOffer(request), (offer) => {
      this.offers.push(offer);
      this.showCreateForm = false;
      this.offerForm.reset({ currency: 'USD', offerLetter: 'We are pleased to offer you this position...' });
    }, 'The offer could not be saved.');
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
    this.step(this.applicationApiService.sendOffer({ offerId: offer.id, expiresInDays: 14 }), (updatedOffer) => {
      this.replaceOffer(updatedOffer);
      this.notice = updatedOffer.candidateHasAccount
        ? `Sent. ${updatedOffer.candidateName || 'The candidate'} answers on AIRRAL by the end of ${this.formatDate(updatedOffer.expiresAt)}.`
        : `Sent. ${updatedOffer.candidateName || 'The candidate'} has no AIRRAL account, so record their answer here when you have it.`;
    }, 'The offer could not be sent.');
  }

  /** For a candidate HR added by hand: they have no account, so HR records their answer. */
  recordAnswer(offer: Offer, accepted: boolean) {
    const name = offer.candidateName || 'the candidate';
    if (!confirm(accepted ? `Record that ${name} accepted? They will be marked hired.` : `Record that ${name} declined?`)) return;
    const answer$ = accepted
      ? this.applicationApiService.acceptOffer(offer.id)
      : this.applicationApiService.declineOffer(offer.id);
    this.step(answer$, (updatedOffer) => {
      this.replaceOffer(updatedOffer);
      this.notice = accepted ? `${name} is marked hired.` : `Recorded that ${name} declined.`;
    }, 'The answer could not be recorded.');
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

    this.step(this.applicationApiService.withdrawOffer(offer.id), (updatedOffer) => {
      this.replaceOffer(updatedOffer);
      this.notice = 'Offer withdrawn.';
    }, 'The offer could not be withdrawn.');
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
