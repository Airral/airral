import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-priority-queue',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './priority-queue.component.html',
  styleUrl: './priority-queue.component.css',
})
export class PriorityQueueComponent {}
