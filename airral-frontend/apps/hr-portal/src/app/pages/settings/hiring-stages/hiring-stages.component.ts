import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface HiringStage {
  id: number;
  name: string;
  order: number;
  duration: number; // days
  isActive: boolean;
  requiresApproval: boolean;
  description: string;
}

@Component({
  selector: 'app-hiring-stages',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './hiring-stages.component.html',
  styleUrl: './hiring-stages.component.css',
})
export class HiringStagesComponent {
  editMode = false;
  editingStage: HiringStage | null = null;

  stages: HiringStage[] = [
    { id: 1, name: 'Application Received', order: 1, duration: 1, isActive: true, requiresApproval: false, description: 'Initial application submission' },
    { id: 2, name: 'Resume Screening', order: 2, duration: 2, isActive: true, requiresApproval: true, description: 'HR reviews resume and qualifications' },
    { id: 3, name: 'Phone Screen', order: 3, duration: 3, isActive: true, requiresApproval: true, description: 'Initial phone conversation with recruiter' },
    { id: 4, name: 'Technical Assessment', order: 4, duration: 5, isActive: true, requiresApproval: false, description: 'Candidate completes technical test or coding challenge' },
    { id: 5, name: 'First Round Interview', order: 5, duration: 7, isActive: true, requiresApproval: true, description: 'Interview with hiring manager or team lead' },
    { id: 6, name: 'Second Round Interview', order: 6, duration: 7, isActive: true, requiresApproval: true, description: 'Panel interview with team members' },
    { id: 7, name: 'Final Round Interview', order: 7, duration: 5, isActive: true, requiresApproval: true, description: 'Interview with department head or executive' },
    { id: 8, name: 'Background Check', order: 8, duration: 5, isActive: true, requiresApproval: false, description: 'Verify employment history and credentials' },
    { id: 9, name: 'Offer Extended', order: 9, duration: 3, isActive: true, requiresApproval: true, description: 'Formal offer sent to candidate' },
    { id: 10, name: 'Offer Accepted', order: 10, duration: 1, isActive: true, requiresApproval: false, description: 'Candidate accepts the offer' },
  ];

  get totalPipelineDays(): number {
    return this.stages
      .filter(s => s.isActive)
      .reduce((sum, s) => sum + s.duration, 0);
  }

  get activeStagesCount(): number {
    return this.stages.filter(s => s.isActive).length;
  }

  toggleStage(stage: HiringStage): void {
    stage.isActive = !stage.isActive;
    this.saveChanges();
  }

  editStage(stage: HiringStage): void {
    this.editingStage = { ...stage };
    this.editMode = true;
  }

  saveStageEdit(): void {
    if (this.editingStage) {
      const index = this.stages.findIndex(s => s.id === this.editingStage!.id);
      if (index !== -1) {
        this.stages[index] = { ...this.editingStage };
      }
      this.saveChanges();
      this.cancelEdit();
    }
  }

  cancelEdit(): void {
    this.editMode = false;
    this.editingStage = null;
  }

  saveChanges(): void {
    // TODO: Call API to save changes
    console.log('Saving hiring stages:', this.stages);
  }

  moveUp(stage: HiringStage): void {
    const index = this.stages.findIndex(s => s.id === stage.id);
    if (index > 0) {
      const temp = this.stages[index - 1].order;
      this.stages[index - 1].order = stage.order;
      stage.order = temp;
      this.stages.sort((a, b) => a.order - b.order);
      this.saveChanges();
    }
  }

  moveDown(stage: HiringStage): void {
    const index = this.stages.findIndex(s => s.id === stage.id);
    if (index < this.stages.length - 1) {
      const temp = this.stages[index + 1].order;
      this.stages[index + 1].order = stage.order;
      stage.order = temp;
      this.stages.sort((a, b) => a.order - b.order);
      this.saveChanges();
    }
  }
}
