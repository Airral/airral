import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { CareerEvent } from '../../models/candidate-dashboard.models';

@Component({
  selector: 'app-career-events',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule],
  templateUrl: './career-events.component.html',
  styleUrls: ['./career-events.component.css']
})
export class CareerEventsComponent {
  @Input() events: CareerEvent[] = [];
  @Output() reserveEvent = new EventEmitter<CareerEvent>();

  private readonly reservedEvents = new Set<string>();

  isReserved(event: CareerEvent): boolean {
    return this.reservedEvents.has(event.title);
  }

  handleReserve(event: CareerEvent): void {
    this.reservedEvents.add(event.title);
    this.reserveEvent.emit(event);
  }
}
