import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

export interface JobFormData {
  title: string;
  department: string;
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
  @Input() linkedInConnected = false;  // Is LinkedIn integration active?
  @Input() formData: JobFormData = {
    title: '',
    department: '',
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
  @Output() connectLinkedIn = new EventEmitter<void>();

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
