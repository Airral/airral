// apps/admin-portal/src/app/pages/users/users.component.ts
import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.css']
})
export class UsersComponent {
  users = [
    { id: 1, email: 'admin@airral.com', name: 'Admin User', role: 'ADMIN', active: true },
    { id: 2, email: 'hr@airral.com', name: 'HR Manager', role: 'HR_MANAGER', active: true },
    { id: 3, email: 'applicant@airral.com', name: 'John Doe', role: 'APPLICANT', active: true },
    { id: 4, email: 'jane@example.com', name: 'Jane Smith', role: 'HR_MANAGER', active: false }
  ];

  loading = false;
  showCreateForm = false;
  searchTerm = '';
  selectedRole = 'ALL';
  newUser = { email: '', name: '', role: 'APPLICANT' };

  createUser() {
    if (this.newUser.email && this.newUser.name) {
      this.users.push({
        id: Math.max(...this.users.map(u => u.id)) + 1,
        email: this.newUser.email,
        name: this.newUser.name,
        role: this.newUser.role,
        active: true
      });
      this.newUser = { email: '', name: '', role: 'APPLICANT' };
      this.showCreateForm = false;
    }
  }

  toggleUserActive(userId: number) {
    const user = this.users.find(u => u.id === userId);
    if (user) {
      user.active = !user.active;
    }
  }

  deleteUser(userId: number) {
    this.users = this.users.filter(u => u.id !== userId);
  }

  get filteredUsers() {
    return this.users.filter((user) => {
      const byRole = this.selectedRole === 'ALL' || user.role === this.selectedRole;
      const term = this.searchTerm.trim().toLowerCase();
      const bySearch =
        term === '' ||
        user.name.toLowerCase().includes(term) ||
        user.email.toLowerCase().includes(term);

      return byRole && bySearch;
    });
  }

  get activeUsersCount(): number {
    return this.users.filter((user) => user.active).length;
  }

  get hrUsersCount(): number {
    return this.users.filter((user) => user.role === 'HR_MANAGER').length;
  }
}
