import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-kpi-cards',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './kpi-cards.component.html',
  styleUrl: './kpi-cards.component.css',
})
export class KpiCardsComponent {
  @Input() openPositions = 0;
  @Input() totalApplications = 0;
  @Input() scheduledInterviews = 0;
  @Input() hires = 0;
}
