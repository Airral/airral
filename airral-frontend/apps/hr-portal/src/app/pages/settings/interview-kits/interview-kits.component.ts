import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { InterviewKitApiService } from '@airral/shared-api';
import { InterviewKit, InterviewKitRequest } from '@airral/shared-types';
import { finalize } from 'rxjs';
import { messageFrom } from '../api-message';

interface KitDraft {
  name: string;
  description: string;
  durationMinutes: number;
  questions: { text: string; category: string }[];
  criteria: { name: string; category: string; weight: number }[];
}

/** What interviewers rate when a job has no kit. The API holds the same list. */
const STANDARD_CRITERIA = [
  { name: 'Skills for the role', category: 'Skills', weight: 3 },
  { name: 'Problem solving', category: 'Skills', weight: 3 },
  { name: 'Communication', category: 'Working style', weight: 2 },
  { name: 'Working with others', category: 'Working style', weight: 2 },
  { name: 'Relevant experience', category: 'Experience', weight: 2 },
  { name: 'Motivation for this role', category: 'Experience', weight: 1 },
];

/**
 * A company's interview kits: the questions interviewers ask and the criteria
 * they rate. A job picks its kit in the job form.
 */
@Component({
  selector: 'app-interview-kits',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './interview-kits.component.html',
  styleUrls: ['../settings-page.css', './interview-kits.component.css'],
})
export class InterviewKitsComponent implements OnInit {
  private readonly kitApi = inject(InterviewKitApiService);

  readonly durations = [30, 45, 60, 90, 120];
  readonly weights = [
    { value: 1, label: 'Counts a little' },
    { value: 2, label: 'Counts' },
    { value: 3, label: 'Counts a lot' },
  ];
  readonly standardCriteria = STANDARD_CRITERIA;

  kits: InterviewKit[] = [];
  loading = true;
  loadError = '';

  draft: KitDraft | null = null;
  editingId: number | null = null;
  saving = false;
  formError = '';
  message = '';

  confirmingRemove: number | null = null;
  removing: number | null = null;
  rowMessage: Record<number, string> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    this.kitApi
      .list()
      .pipe(finalize(() => (this.loading = false)))
      .subscribe({
        next: (kits) => (this.kits = kits),
        error: (error) => (this.loadError = messageFrom(error, 'Your interview kits could not be loaded. Try again.')),
      });
  }

  /** A new kit starts from the standard criteria, to adapt rather than type from nothing. */
  newKit(): void {
    this.editingId = null;
    this.formError = '';
    this.message = '';
    this.draft = {
      name: '',
      description: '',
      durationMinutes: 60,
      questions: [{ text: '', category: '' }],
      criteria: STANDARD_CRITERIA.map((criterion) => ({ ...criterion })),
    };
  }

  edit(kit: InterviewKit): void {
    this.editingId = kit.id;
    this.formError = '';
    this.message = '';
    this.draft = {
      name: kit.name,
      description: kit.description ?? '',
      durationMinutes: kit.durationMinutes || 60,
      questions: kit.questions.map((question) => ({ text: question.text, category: question.category ?? '' })),
      criteria: (kit.criteria.length ? kit.criteria : STANDARD_CRITERIA).map((criterion) => ({
        name: criterion.name,
        category: criterion.category ?? '',
        weight: criterion.weight || 2,
      })),
    };
  }

  cancel(): void {
    this.draft = null;
    this.editingId = null;
    this.formError = '';
  }

  addQuestion(): void {
    this.draft?.questions.push({ text: '', category: '' });
  }

  removeQuestion(index: number): void {
    this.draft?.questions.splice(index, 1);
  }

  addCriterion(): void {
    this.draft?.criteria.push({ name: '', category: '', weight: 2 });
  }

  removeCriterion(index: number): void {
    this.draft?.criteria.splice(index, 1);
  }

  save(): void {
    const draft = this.draft;
    if (!draft || this.saving) return;
    if (!draft.name.trim()) {
      this.formError = 'Give the kit a name.';
      return;
    }
    const request: InterviewKitRequest = {
      name: draft.name.trim(),
      description: draft.description.trim() || undefined,
      durationMinutes: draft.durationMinutes,
      questions: draft.questions
        .filter((question) => question.text.trim())
        .map((question) => ({ text: question.text.trim(), category: question.category.trim() || null })),
      criteria: draft.criteria
        .filter((criterion) => criterion.name.trim())
        .map((criterion) => ({ name: criterion.name.trim(), category: criterion.category.trim() || null, weight: criterion.weight })),
    };

    this.saving = true;
    this.formError = '';
    const saved$ = this.editingId ? this.kitApi.update(this.editingId, request) : this.kitApi.create(request);
    saved$.pipe(finalize(() => (this.saving = false))).subscribe({
      next: (kit) => {
        const others = this.kits.filter((existing) => existing.id !== kit.id);
        this.kits = [...others, kit].sort((a, b) => a.name.localeCompare(b.name));
        this.message = this.editingId ? `Saved ${kit.name}.` : `Added ${kit.name}. Pick it for a job in the job form.`;
        this.draft = null;
        this.editingId = null;
      },
      error: (error) => (this.formError = messageFrom(error, 'The kit could not be saved. Try again.')),
    });
  }

  remove(kit: InterviewKit): void {
    this.removing = kit.id;
    this.kitApi
      .remove(kit.id)
      .pipe(finalize(() => (this.removing = null)))
      .subscribe({
        next: () => {
          this.kits = this.kits.filter((existing) => existing.id !== kit.id);
          this.confirmingRemove = null;
          this.message = `Removed ${kit.name}. Its jobs now use the standard criteria.`;
        },
        error: (error) => (this.rowMessage[kit.id] = messageFrom(error, 'The kit could not be removed. Try again.')),
      });
  }

  criteriaSummary(kit: InterviewKit): string {
    return kit.criteria.length ? `${kit.criteria.length} criteria` : 'Standard criteria';
  }

  trackIndex(index: number): number {
    return index;
  }
}
