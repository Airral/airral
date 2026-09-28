package com.airral.domain.enums;

/**
 * Where an application stands, as the applicant sees it.
 *
 * <p>Companies move applications through finer stages than an applicant needs
 * to see. A shortlist, for one, is a company's working list, and "Decision
 * needed" is a note to the team. This is the part of the stage that is the
 * applicant's business.
 */
public enum ApplicantStage {
    APPLIED,
    IN_REVIEW,
    INTERVIEWING,
    OFFER,
    HIRED,
    NOT_SELECTED,
    WITHDRAWN;

    public static ApplicantStage of(ApplicationStatus status) {
        if (status == null) return APPLIED;
        return switch (status) {
            case SUBMITTED -> APPLIED;
            case UNDER_REVIEW, SHORTLISTED -> IN_REVIEW;
            case INTERVIEW_SCHEDULED, INTERVIEWED -> INTERVIEWING;
            case OFFER_EXTENDED -> OFFER;
            case HIRED -> HIRED;
            case REJECTED -> NOT_SELECTED;
            case WITHDRAWN -> WITHDRAWN;
        };
    }
}
