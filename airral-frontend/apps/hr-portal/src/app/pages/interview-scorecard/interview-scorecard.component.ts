import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApplicationApiService } from '@airral/shared-api';
import { Recommendation, Scorecard, ScoreRating } from '@airral/shared-types';
import { wallTimeToDate } from '@airral/shared-utils';
import { finalize } from 'rxjs';

interface RatingOption {
  value: number;
  label: string;
}

/**
 * An interviewer's scorecard for one interview. It stays a draft, seen only by
 * them, until they submit it; then the hiring team reads it and it no longer
 * changes.
 */
@Component({
  selector: 'app-interview-scorecard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './interview-scorecard.component.html',
  styleUrl: './interview-scorecard.component.css',
})
export class InterviewScorecardComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly applicationApi = inject(ApplicationApiService);

  readonly ratingOptions: RatingOption[] = [
    { value: 1, label: 'Poor' },
    { value: 2, label: 'Below the bar' },
    { value: 3, label: 'Meets the bar' },
    { value: 4, label: 'Strong' },
    { value: 5, label: 'Excellent' },
  ];
  readonly recommendations: { value: Recommendation; label: string }[] = [
    { value: 'STRONG_HIRE', label: 'Strong hire' },
    { value: 'HIRE', label: 'Hire' },
    { value: 'NO_HIRE', label: 'No hire' },
    { value: 'STRONG_NO_HIRE', label: 'Strong no hire' },
  ];

  interviewId: number | null = null;
  scorecard: Scorecard | null = null;
  ratings: ScoreRating[] = [];
  overallNotes = '';
  recommendation: Recommendation | null = null;

  loading = true;
  saving = false;
  error = '';
  message = '';

  ngOnInit(): void {
    const id = Number(this.route.snapshot.queryParamMap.get('interviewId'));
    if (!Number.isInteger(id) || id <= 0) {
      this.loading = false;
      return;
    }
    this.interviewId = id;
    this.applicationApi
      .getMyScorecard(id)
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (scorecard) => this.show(scorecard),
        error: (error: { status?: number; message?: string }) => {
          this.error = error?.status === 404
            ? 'This interview is not one of yours. Scorecards are for the interviewers on it.'
            : error?.message || 'The scorecard could not be loaded. Try again.';
        },
      });
  }

  get submitted(): boolean {
    return this.scorecard?.status === 'SUBMITTED';
  }

  get complete(): boolean {
    return this.ratings.length > 0 && this.ratings.every((rating) => !!rating.rating) && !!this.recommendation;
  }

  get ratedCount(): number {
    return this.ratings.filter((rating) => !!rating.rating).length;
  }

  startsAt(scorecard: Scorecard): Date | null {
    return scorecard.interviewDate ? wallTimeToDate(scorecard.interviewDate, scorecard.timeZone) : null;
  }

  weightLabel(weight?: number | null): string {
    return weight === 3 ? 'Counts a lot' : weight === 1 ? 'Counts a little' : 'Counts';
  }

  ratingLabel(value?: number | null): string {
    return this.ratingOptions.find((option) => option.value === value)?.label ?? 'Not rated';
  }

  recommendationLabel(value?: Recommendation | null): string {
    return this.recommendations.find((option) => option.value === value)?.label ?? '';
  }

  rate(rating: ScoreRating, value: number): void {
    if (this.submitted) return;
    rating.rating = value;
  }

  save(submit: boolean): void {
    if (!this.interviewId || this.saving || this.submitted) return;
    if (submit && !this.complete) {
      this.error = 'Rate every criterion and choose a recommendation before you submit.';
      return;
    }
    this.saving = true;
    this.error = '';
    this.message = '';
    this.applicationApi
      .saveMyScorecard(this.interviewId, {
        ratings: this.ratings.map((rating) => ({
          criterion: rating.criterion,
          rating: rating.rating ?? null,
          notes: rating.notes?.trim() || null,
        })),
        overallNotes: this.overallNotes.trim() || undefined,
        recommendation: this.recommendation,
        submit,
      })
      .pipe(finalize(() => (this.saving = false)))
      .subscribe({
        next: (scorecard) => {
          this.show(scorecard);
          this.message = submit
            ? 'Submitted. The hiring team can read it now.'
            : 'Draft saved. Only you can see it until you submit.';
        },
        error: (error: { message?: string }) => {
          this.error = error?.message || 'The scorecard could not be saved. Try again.';
        },
      });
  }

  openResume(): void {
    if (!this.interviewId) return;
    // The tab opens inside the click, so the browser does not block it.
    const tab = window.open('', '_blank');
    this.applicationApi.downloadInterviewResume(this.interviewId).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        if (tab) {
          tab.location.href = url;
        } else {
          window.open(url, '_blank');
        }
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (error: { status?: number }) => {
        tab?.close();
        this.error = error?.status === 404
          ? 'No resume is attached to this application.'
          : 'The resume could not be opened. Try again.';
      },
    });
  }

  private show(scorecard: Scorecard): void {
    this.scorecard = scorecard;
    this.ratings = scorecard.ratings.map((rating) => ({ ...rating }));
    this.overallNotes = scorecard.overallNotes ?? '';
    this.recommendation = scorecard.recommendation ?? null;
  }
}
