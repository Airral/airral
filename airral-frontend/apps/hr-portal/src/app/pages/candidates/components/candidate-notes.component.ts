// apps/hr-portal/src/app/pages/candidates/components/candidate-notes.component.ts
import { Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '@airral/shared-auth';

interface Note {
  id: number;
  author: string;
  content: string;
  createdAt: Date;
}

@Component({
  selector: 'app-candidate-notes',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="notes-section">
      <h3>Notes & Comments</h3>

      <div class="note-form">
        <textarea
          [(ngModel)]="newNoteContent"
          placeholder="Add a note about this candidate..."
          rows="3"
        ></textarea>
        <button
          (click)="addNote()"
          [disabled]="!newNoteContent.trim()"
          class="btn-primary"
        >
          Add Note
        </button>
      </div>

      <div class="notes-list">
        <div class="note" *ngFor="let note of notes">
          <div class="note-header">
            <strong>{{ note.author }}</strong>
            <span class="note-time">{{ note.createdAt | date: 'short' }}</span>
          </div>
          <p class="note-content">{{ note.content }}</p>
        </div>

        <p *ngIf="notes.length === 0" class="empty-state">
          No notes yet. Add the first one above.
        </p>
      </div>
    </div>
  `,
  styles: [`
    .notes-section {
      background: white;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 1.5rem;
      margin-bottom: 1.5rem;
    }

    h3 {
      margin: 0 0 1rem;
      font-size: 1.1rem;
      color: #0f172a;
    }

    .note-form {
      display: flex;
      flex-direction: column;
      gap: 0.75rem;
      margin-bottom: 1.5rem;
      padding-bottom: 1.5rem;
      border-bottom: 1px solid #e2e8f0;
    }

    textarea {
      padding: 0.75rem;
      border: 1px solid #cbd5e1;
      border-radius: 8px;
      font-family: inherit;
      font-size: 0.95rem;
      resize: vertical;
    }

    textarea:focus {
      outline: none;
      border-color: #0f9d78;
    }

    .btn-primary {
      align-self: flex-end;
      background: #0f9d78;
      color: white;
      border: none;
      padding: 0.5rem 1rem;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
    }

    .btn-primary:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .notes-list {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .note {
      background: #f8fafc;
      padding: 1rem;
      border-radius: 8px;
      border-left: 3px solid #0f9d78;
    }

    .note-header {
      display: flex;
      justify-content: space-between;
      margin-bottom: 0.5rem;
    }

    .note-time {
      font-size: 0.85rem;
      color: #64748b;
    }

    .note-content {
      margin: 0;
      color: #334155;
      line-height: 1.5;
    }

    .empty-state {
      text-align: center;
      color: #94a3b8;
      padding: 2rem;
      margin: 0;
    }
  `]
})
export class CandidateNotesComponent {
  @Input() candidateId!: number;

  private authService = inject(AuthService);

  notes: Note[] = [
    {
      id: 1,
      author: 'Sarah HR',
      content: 'Strong technical background. Impressive portfolio with React projects.',
      createdAt: new Date('2026-04-20T10:30:00')
    },
    {
      id: 2,
      author: 'Mike Manager',
      content: 'Cultural fit looks good. Excited about our mission.',
      createdAt: new Date('2026-04-21T14:15:00')
    }
  ];

  newNoteContent = '';

  addNote(): void {
    if (!this.newNoteContent.trim()) return;

    const user = this.authService.getCurrentUser();
    const newNote: Note = {
      id: this.notes.length + 1,
      author: user?.email?.split('@')[0] || 'Anonymous',
      content: this.newNoteContent,
      createdAt: new Date()
    };

    this.notes.unshift(newNote);
    this.newNoteContent = '';

    // TODO: Call backend API to save note
    console.log('Save note to backend:', { candidateId: this.candidateId, note: newNote });
  }
}
