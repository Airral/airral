import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Department } from '@airral/shared-api';
import { InterviewKit, User } from '@airral/shared-types';

export interface JobFormData {
  title: string;
  /** One of the company's departments, or none. */
  departmentId: number | null;
  /** A manager or HR manager who sees and interviews this job's candidates. */
  hiringManagerId: number | null;
  /** The interview kit this job's interviews use, or none for the standard criteria. */
  interviewKitId: number | null;
  location: string;
  employmentType: string;
  salaryMin: string;
  salaryMax: string;
  description: string;
  requirements: string;
  niceToHave: string;
  atsKeywords: string;
  linkedInEnabled: boolean;  // Post to LinkedIn
}

@Component({
  selector: 'app-job-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './job-dialog.component.html',
  styleUrl: './job-dialog.component.css',
})
export class JobDialogComponent {
  @Input() visible = false;
  @Input() editMode = false;
  @Input() saving = false;
  @Input() departments: Department[] = [];
  /** Active managers and HR managers in the company. */
  @Input() hiringManagers: User[] = [];
  /** The name of the job's hiring manager as the job has it, for one no longer on the hiring team. */
  @Input() hiringManagerName: string | null = null;
  @Input() interviewKits: InterviewKit[] = [];

  /**
   * The job's hiring manager when they are no longer among the team's hiring
   * managers (switched off, or moved to another role). Shown as they are, so
   * HR sees who it was and picks someone else; saving without a change keeps them.
   */
  get formerHiringManagerId(): number | null {
    const id = this.formData.hiringManagerId;
    return id != null && !this.hiringManagers.some((person) => person.id === id) ? id : null;
  }
  @Input() formData: JobFormData = {
    title: '',
    departmentId: null,
    hiringManagerId: null,
    interviewKitId: null,
    location: '',
    employmentType: 'Full-time',
    salaryMin: '',
    salaryMax: '',
    description: '',
    requirements: '',
    niceToHave: '',
    atsKeywords: '',
    linkedInEnabled: false,
  };

  @Output() dismiss = new EventEmitter<void>();
  @Output() saveDraft = new EventEmitter<void>();
  @Output() publish = new EventEmitter<void>();

  onCancel(): void {
    this.dismiss.emit();
  }

  onSaveDraft(): void {
    this.saveDraft.emit();
  }

  onPublish(): void {
    this.publish.emit();
  }

  onOverlayClick(): void {
    this.dismiss.emit();
  }

  onDialogClick(event: Event): void {
    event.stopPropagation();
  }
}
