import { CommonModule } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Department, DepartmentApiService } from '@airral/shared-api';
import { messageFrom } from '../api-message';

/**
 * A company's departments: the list jobs and people are filed under. Renaming
 * one renames it everywhere; removing one leaves its jobs and people without a
 * department.
 */
@Component({
  selector: 'app-department-settings',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './departments.component.html',
  styleUrl: '../settings-page.css',
})
export class DepartmentsComponent implements OnInit {
  private readonly departmentApi = inject(DepartmentApiService);

  departments: Department[] = [];
  loading = true;
  loadError = '';

  newName = '';
  newDescription = '';
  adding = false;
  addMessage = '';
  addError = '';

  editingId: number | null = null;
  editName = '';
  editDescription = '';
  confirmingRemoveId: number | null = null;
  readonly busy = new Set<number>();
  rowMessage: Record<number, string> = {};

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    this.departmentApi.list().subscribe({
      next: (departments) => {
        this.departments = sortByName(departments);
        this.loading = false;
      },
      error: () => {
        this.loading = false;
        this.loadError = 'We could not load your departments. Reload the page to try again.';
      },
    });
  }

  add(): void {
    const name = this.newName.trim();
    if (this.adding) return;
    if (!name) {
      this.addError = 'Give the department a name.';
      return;
    }
    this.adding = true;
    this.addError = '';
    this.addMessage = '';
    this.departmentApi.create({ name, description: this.newDescription.trim() || undefined }).subscribe({
      next: (department) => {
        this.adding = false;
        this.departments = sortByName([...this.departments, department]);
        this.addMessage = `Added ${department.name}.`;
        this.newName = '';
        this.newDescription = '';
      },
      error: (error) => {
        this.adding = false;
        this.addError = messageFrom(error, 'We could not add the department. Try again.');
      },
    });
  }

  startEdit(department: Department): void {
    this.confirmingRemoveId = null;
    this.editingId = department.id;
    this.editName = department.name;
    this.editDescription = department.description ?? '';
    delete this.rowMessage[department.id];
  }

  cancelEdit(): void {
    this.editingId = null;
  }

  saveEdit(department: Department): void {
    const name = this.editName.trim();
    if (this.busy.has(department.id)) return;
    if (!name) {
      this.rowMessage[department.id] = 'Give the department a name.';
      return;
    }
    this.busy.add(department.id);
    this.departmentApi.update(department.id, { name, description: this.editDescription.trim() || undefined }).subscribe({
      next: (updated) => {
        this.busy.delete(department.id);
        this.editingId = null;
        this.departments = sortByName(this.departments.map((item) => (item.id === updated.id ? updated : item)));
        this.rowMessage[updated.id] =
          updated.name === department.name ? 'Saved.' : 'Renamed. Its jobs and people show the new name.';
      },
      error: (error) => {
        this.busy.delete(department.id);
        this.rowMessage[department.id] = messageFrom(error, 'We could not save the department. Try again.');
      },
    });
  }

  askRemove(department: Department): void {
    this.editingId = null;
    this.confirmingRemoveId = department.id;
    delete this.rowMessage[department.id];
  }

  keep(): void {
    this.confirmingRemoveId = null;
  }

  remove(department: Department): void {
    if (this.busy.has(department.id)) return;
    this.busy.add(department.id);
    this.departmentApi.remove(department.id).subscribe({
      next: () => {
        this.busy.delete(department.id);
        this.confirmingRemoveId = null;
        this.departments = this.departments.filter((item) => item.id !== department.id);
      },
      error: (error) => {
        this.busy.delete(department.id);
        this.confirmingRemoveId = null;
        this.rowMessage[department.id] = messageFrom(error, 'We could not remove the department. Try again.');
      },
    });
  }
}

function sortByName(departments: Department[]): Department[] {
  return [...departments].sort((a, b) => a.name.localeCompare(b.name));
}
