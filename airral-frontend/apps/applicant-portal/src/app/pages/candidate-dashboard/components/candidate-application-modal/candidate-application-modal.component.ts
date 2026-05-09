import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CandidateApplicationView } from '@airral/shared-types';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-candidate-application-modal',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatDividerModule, MatChipsModule, MatCardModule],
  templateUrl: './candidate-application-modal.component.html',
  styleUrls: ['./candidate-application-modal.component.css']
})
export class CandidateApplicationModalComponent {
  @Input() visible = false;
  @Input() application: CandidateApplicationView | null = null;

  @Output() close = new EventEmitter<void>();
  @Output() acceptOffer = new EventEmitter<CandidateApplicationView>();
  @Output() declineOffer = new EventEmitter<CandidateApplicationView>();

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    });
  }

  formatCurrency(value: number, currency: string): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(value);
  }
}
