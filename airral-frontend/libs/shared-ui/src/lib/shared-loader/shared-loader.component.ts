import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'airral-shared-loader',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './shared-loader.component.html',
  styleUrls: ['./shared-loader.component.css']
})
export class SharedLoaderComponent {
  @Input() lines = 4;
  @Input() message = 'Loading...';

  get widths(): number[] {
    return Array.from({ length: this.lines }, (_, index) => Math.max(45, 100 - index * 12));
  }
}
