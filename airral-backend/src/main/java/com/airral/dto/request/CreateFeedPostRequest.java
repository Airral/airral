package com.airral.dto.request;

import lombok.Data;

@Data
public class CreateFeedPostRequest {
    private String postType;   // COMPANY_SIGNAL | HIRING_PULSE | ROLE_SPOTLIGHT | COMMUNITY_TIP | CAREER_UPDATE | JOB_SEARCH_ASK | INTERVIEW_NOTE | SALARY_INTEL | REFERRAL_OFFER | FOUNDER_UPDATE
    private String visibility; // PUBLIC | AUTHENTICATED | APPLICANTS_ONLY
    private String topic;
    private String content;
    private Long linkedJobId;
    private String linkedExternalJobKey;
    private String targetType;  // JOB | COMPANY | ROOM | EVENT | GENERAL
    private String targetLabel;
}
