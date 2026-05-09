import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-pipeline-mix',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pipeline-mix.component.html',
  styleUrl: './pipeline-mix.component.css',
})
export class PipelineMixComponent {
  @Input() submitted = 0;
  @Input() underReview = 0;
  @Input() shortlisted = 0;
  @Input() interviewed = 0;
}
