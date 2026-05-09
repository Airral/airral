import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { UserApiService } from '@airral/shared-api';
import { AuthService } from '@airral/shared-auth';

interface EmployeeProfile {
  id: number;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  department: string;
  jobTitle: string;
  manager: string;
  startDate: string;
  employmentType: string;
  location: string;
  emergencyContact: {
    name: string;
    relationship: string;
    phone: string;
  };
}

@Component({
  selector: 'app-employee-profile',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './profile.component.html',
  styleUrl: './profile.component.css',
})
export class ProfileComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly userApi = inject(UserApiService);

  profile: EmployeeProfile | null = null;
  loading = true;
  editing = false;
  error: string | null = null;

  ngOnInit(): void {
    this.loadProfile();
  }

  loadProfile(): void {
    const user = this.authService.getCurrentUser();
    if (!user?.id) {
      this.error = 'Unable to load profile';
      this.loading = false;
      return;
    }

    this.loading = true;
    this.error = null;

    this.userApi.getUserById(user.id).subscribe({
      next: (loadedUser) => {
        this.profile = {
          id: loadedUser.id,
          firstName: loadedUser.firstName || '',
          lastName: loadedUser.lastName || '',
          email: loadedUser.email,
          phone: loadedUser.phone || '',
          department: loadedUser.department || '',
          jobTitle: loadedUser.jobTitle || '',
          manager: loadedUser.managerId ? `Manager #${loadedUser.managerId}` : '',
          startDate: loadedUser.createdAt ? loadedUser.createdAt.slice(0, 10) : '',
          employmentType: '',
          location: '',
          emergencyContact: {
            name: '',
            relationship: '',
            phone: '',
          },
        };
        this.loading = false;
      },
      error: () => {
        this.error = 'Failed to load profile';
        this.loading = false;
      },
    });
  }

  toggleEdit(): void {
    this.editing = !this.editing;
  }

  saveProfile(): void {
    if (!this.profile) return;

    this.userApi.updateUser(this.profile.id, {
      firstName: this.profile.firstName,
      lastName: this.profile.lastName,
      phone: this.profile.phone,
      department: this.profile.department,
      jobTitle: this.profile.jobTitle,
    }).subscribe({
      next: () => {
        this.editing = false;
        this.error = null;
      },
      error: () => {
        this.error = 'Failed to save profile';
      },
    });
  }

  cancelEdit(): void {
    this.editing = false;
    this.loadProfile(); // Reload original data
  }
}
