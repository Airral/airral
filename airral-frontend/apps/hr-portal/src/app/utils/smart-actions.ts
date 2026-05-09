// apps/hr-portal/src/app/utils/smart-actions.ts
import { Application, ApplicationStatus } from '@airral/shared-types';

export interface SmartAction {
  label: string;
  action: string;
  isPrimary: boolean;
  requiresInput?: boolean;
}

/**
 * Determines the next logical action for a candidate based on their current status
 * This reduces UI clutter by showing only relevant actions
 */
export function getSmartActions(candidate: Application): SmartAction[] {
  switch (candidate.status) {
    case ApplicationStatus.SUBMITTED:
      return [
        { label: 'Review Application', action: 'review', isPrimary: true },
        { label: 'Reject', action: 'reject', isPrimary: false },
      ];

    case ApplicationStatus.UNDER_REVIEW:
      return [
        { label: 'Shortlist', action: 'shortlist', isPrimary: true },
        { label: 'Reject', action: 'reject', isPrimary: false },
      ];

    case ApplicationStatus.SHORTLISTED:
      return [
        { label: 'Schedule Interview', action: 'schedule', isPrimary: true, requiresInput: true },
        { label: 'Reject', action: 'reject', isPrimary: false },
      ];

    case ApplicationStatus.INTERVIEW_SCHEDULED:
      return [
        { label: 'View Interview', action: 'view_interview', isPrimary: true },
        { label: 'Reschedule', action: 'reschedule', isPrimary: false },
      ];

    case ApplicationStatus.INTERVIEWED:
      return [
        { label: 'Make Decision', action: 'decide', isPrimary: true },
        { label: 'Schedule Follow-up', action: 'followup', isPrimary: false },
      ];

    case ApplicationStatus.OFFER_EXTENDED:
      return [
        { label: 'Check Offer Status', action: 'check_offer', isPrimary: true },
        { label: 'Withdraw Offer', action: 'withdraw', isPrimary: false },
      ];

    case ApplicationStatus.HIRED:
      return [
        { label: 'View Details', action: 'view', isPrimary: true },
      ];

    case ApplicationStatus.REJECTED:
      return [
        { label: 'View Details', action: 'view', isPrimary: true },
      ];

    default:
      return [
        { label: 'View Details', action: 'view', isPrimary: true },
      ];
  }
}

export function getPrimaryAction(candidate: Application): SmartAction | null {
  const actions = getSmartActions(candidate);
  return actions.find(a => a.isPrimary) || null;
}

export function getSecondaryActions(candidate: Application): SmartAction[] {
  const actions = getSmartActions(candidate);
  return actions.filter(a => !a.isPrimary);
}

/**
 * Get a user-friendly hint for what should happen next
 */
export function getNextStepHint(candidate: Application): string {
  switch (candidate.status) {
    case ApplicationStatus.SUBMITTED:
      return 'Waiting for initial review';
    case ApplicationStatus.UNDER_REVIEW:
      return 'Under evaluation for shortlisting';
    case ApplicationStatus.SHORTLISTED:
      return 'Ready to schedule interview';
    case ApplicationStatus.INTERVIEW_SCHEDULED:
      return 'Interview pending';
    case ApplicationStatus.INTERVIEWED:
      return 'Awaiting hiring decision';
    case ApplicationStatus.OFFER_EXTENDED:
      return 'Waiting for candidate response';
    case ApplicationStatus.HIRED:
      return 'Successfully hired';
    case ApplicationStatus.REJECTED:
      return 'Application closed';
    default:
      return 'No action needed';
  }
}
