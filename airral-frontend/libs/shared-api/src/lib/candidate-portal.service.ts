// libs/shared-api/src/lib/candidate-portal.service.ts
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { map } from 'rxjs/operators';
import {
  CandidateApplicationView,
  CandidateInterviewView,
  CandidateOfferView,
  CandidateProfile
} from '@airral/shared-types';
import { ApplicationApiService } from './application-api.service';

@Injectable({
  providedIn: 'root'
})
export class CandidatePortalService {
  constructor(private applicationApiService: ApplicationApiService) {}

  getCandidateApplications(candidateEmail: string): Observable<CandidateApplicationView[]> {
    return this.applicationApiService.getJobApplications(1).pipe(
      map(apps => apps.map(app => this.mapApplicationToView(app)))
    );
  }

  getCandidateProfile(email: string): Observable<CandidateProfile> {
    return of({
      id: 1,
      email,
      firstName: 'John',
      lastName: 'Doe',
      phone: '+1-555-0100',
      location: 'San Francisco, CA',
      resume: 'https://example.com/resume.pdf'
    });
  }

  private mapApplicationToView(app: any): CandidateApplicationView {
    return {
      id: app.id,
      jobId: app.jobId,
      jobTitle: app.jobTitle,
      department: 'Engineering',
      status: app.status,
      appliedAt: app.submittedAt,
      lastUpdated: app.updatedAt,
      interviews: [],
      currentOffer: undefined
    };
  }
}
