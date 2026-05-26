import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { JobRoom } from '../../models/candidate-dashboard.models';

@Component({
  selector: 'app-job-rooms',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatChipsModule, MatIconModule],
  templateUrl: './job-rooms.component.html',
  styleUrls: ['./job-rooms.component.css']
})
export class JobRoomsComponent {
  @Input() rooms: JobRoom[] = [];
  @Output() joinRoom = new EventEmitter<JobRoom>();

  private readonly joinedRooms = new Set<string>();

  isJoined(room: JobRoom): boolean {
    return this.joinedRooms.has(room.name);
  }

  handleJoin(room: JobRoom): void {
    this.joinedRooms.add(room.name);
    this.joinRoom.emit(room);
  }
}
