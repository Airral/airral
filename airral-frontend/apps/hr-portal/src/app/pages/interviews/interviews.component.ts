import { Component, OnInit, inject } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule, ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ApplicationApiService } from '@airral/shared-api';
import { Interview, Application } from '@airral/shared-types';
import { forkJoin } from 'rxjs';
import { finalize, timeout } from 'rxjs/operators';

export interface CalendarDay {
  date: Date;
  isCurrentMonth: boolean;
  isToday: boolean;
  interviews: Interview[];
}

@Component({
  selector: 'app-hr-interviews',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule, DatePipe],
  templateUrl: './interviews.component.html',
  styleUrl: './interviews.component.css',
})
export class InterviewsComponent implements OnInit {
  private applicationApiService = inject(ApplicationApiService);
  private fb = inject(FormBuilder);

  interviews: Interview[] = [];
  applications: Application[] = [];
  loading = false;
  error: string | null = null;

  view: 'calendar' | 'list' = 'calendar';
  currentMonth = new Date();
  calendarDays: CalendarDay[] = [];
  selectedDay: CalendarDay | null = null;

  showScheduleForm = false;
  scheduleForm: FormGroup;

  readonly WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

  constructor() {
    this.scheduleForm = this.fb.group({
      applicationId: ['', Validators.required],
      interviewDate: ['', Validators.required],
      interviewTime: ['10:00', Validators.required],
      notes: ['']
    });
  }

  ngOnInit(): void {
    this.buildCalendar();
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.error = null;

    forkJoin({
      interviews: this.applicationApiService.getAllInterviews(),
      applications: this.applicationApiService.getAllApplications(),
    }).pipe(
      timeout(15000),
      finalize(() => {
        this.loading = false;
      })
    ).subscribe({
      next: ({ interviews, applications }) => {
        this.interviews = interviews;
        this.applications = applications;
        this.buildCalendar();
      },
      error: () => {
        this.interviews = [];
        this.applications = [];
        this.buildCalendar();
        this.error = null;
      },
    });
  }

  buildCalendar(): void {
    const year = this.currentMonth.getFullYear();
    const month = this.currentMonth.getMonth();
    const firstDay = new Date(year, month, 1);
    const startDate = new Date(firstDay);
    startDate.setDate(startDate.getDate() - startDate.getDay());

    const today = new Date();
    const days: CalendarDay[] = [];

    for (let i = 0; i < 42; i++) {
      const date = new Date(startDate);
      date.setDate(startDate.getDate() + i);

      const dayInterviews = this.interviews.filter((inv) => {
        const interviewDate = new Date(inv.interviewDate);
        return interviewDate.getFullYear() === date.getFullYear()
          && interviewDate.getMonth() === date.getMonth()
          && interviewDate.getDate() === date.getDate();
      });

      days.push({
        date,
        isCurrentMonth: date.getMonth() === month,
        isToday: date.toDateString() === today.toDateString(),
        interviews: dayInterviews,
      });
    }

    this.calendarDays = days;
  }

  get monthLabel(): string {
    return this.currentMonth.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  }

  get upcomingInterviews(): Interview[] {
    const now = new Date();
    return this.interviews
      .filter((inv) => new Date(inv.interviewDate) >= now && inv.status !== 'COMPLETED')
      .sort((left, right) => new Date(left.interviewDate).getTime() - new Date(right.interviewDate).getTime());
  }

  get completedInterviews(): Interview[] {
    return this.interviews
      .filter((inv) => inv.status === 'COMPLETED')
      .sort((left, right) => new Date(right.interviewDate).getTime() - new Date(left.interviewDate).getTime());
  }

  prevMonth(): void {
    this.currentMonth = new Date(this.currentMonth.getFullYear(), this.currentMonth.getMonth() - 1, 1);
    this.buildCalendar();
    this.selectedDay = null;
  }

  nextMonth(): void {
    this.currentMonth = new Date(this.currentMonth.getFullYear(), this.currentMonth.getMonth() + 1, 1);
    this.buildCalendar();
    this.selectedDay = null;
  }

  selectDay(day: CalendarDay): void {
    this.selectedDay = day;
  }

  selectInterviewAsFocus(interview: Interview): void {
    const interviewDate = new Date(interview.interviewDate);
    const matchedDay = this.calendarDays.find((day) =>
      day.date.getFullYear() === interviewDate.getFullYear()
      && day.date.getMonth() === interviewDate.getMonth()
      && day.date.getDate() === interviewDate.getDate());
    this.selectedDay = matchedDay ?? null;
  }

  openScheduleForm(): void {
    this.showScheduleForm = true;
  }

  cancelSchedule(): void {
    this.showScheduleForm = false;
    this.scheduleForm.reset({ interviewTime: '10:00' });
  }

  submitSchedule(): void {
    if (!this.scheduleForm.valid) {
      return;
    }

    const { applicationId, interviewDate, interviewTime, notes } = this.scheduleForm.value;
    const timestamp = `${interviewDate}T${interviewTime}:00.000Z`;

    this.applicationApiService.scheduleInterview(parseInt(applicationId, 10), timestamp, notes).subscribe({
      next: (interview) => {
        this.interviews = [...this.interviews, interview];
        this.buildCalendar();
        this.cancelSchedule();
      },
      error: () => {
        this.error = 'Failed to schedule interview';
      },
    });
  }

  getInitials(name?: string): string {
    if (!name) {
      return '?';
    }

    return name.split(' ').map((part) => part[0]).join('').toUpperCase().slice(0, 2);
  }

  formatDate(dateString: string): string {
    return new Date(dateString).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  }

  formatTime(dateString: string): string {
    return new Date(dateString).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  }

  getStatusClass(status: string): string {
    return status === 'COMPLETED' ? 'status-completed' : 'status-scheduled';
  }

  getGoogleCalendarLink(interview: Interview): string {
    const start = this.toICSDate(new Date(interview.interviewDate));
    const end = this.toICSDate(this.addHour(interview.interviewDate));
    const title = encodeURIComponent(`Interview: ${interview.candidateName} — ${interview.jobTitle}`);
    return `https://calendar.google.com/calendar/render?action=TEMPLATE&text=${title}&dates=${start}/${end}`;
  }

  getOutlookLink(interview: Interview): string {
    const start = new Date(interview.interviewDate).toISOString();
    const end = this.addHour(interview.interviewDate).toISOString();
    const subject = encodeURIComponent(`Interview: ${interview.candidateName} — ${interview.jobTitle}`);
    return `https://outlook.live.com/calendar/0/deeplink/compose?path=%2Fcalendar%2Faction%2Fcompose&rru=addevent&subject=${subject}&startdt=${start}&enddt=${end}`;
  }

  downloadICS(interview: Interview): void {
    const start = this.toICSDate(new Date(interview.interviewDate));
    const end = this.toICSDate(this.addHour(interview.interviewDate));
    const ics = [
      'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Airral HR//Interview//EN',
      'BEGIN:VEVENT',
      `UID:interview-${interview.id}@airral.com`,
      `DTSTART:${start}`,
      `DTEND:${end}`,
      `SUMMARY:Interview: ${interview.candidateName} — ${interview.jobTitle}`,
      'DESCRIPTION:Scheduled via Airral HR Portal',
      'END:VEVENT',
      'END:VCALENDAR'
    ].join('\r\n');

    const blob = new Blob([ics], { type: 'text/calendar;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `interview-${this.getInitials(interview.candidateName)}.ics`;
    link.click();
    URL.revokeObjectURL(url);
  }

  private toICSDate(date: Date): string {
    return date.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  }

  private addHour(dateString: string): Date {
    const date = new Date(dateString);
    date.setHours(date.getHours() + 1);
    return date;
  }
}
