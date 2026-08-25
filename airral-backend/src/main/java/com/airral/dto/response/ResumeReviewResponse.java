package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response returned after resume upload so the user can review and confirm
 * the extracted data before it drives job matching.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResumeReviewResponse {

    private Long resumeDocumentId;
    private String parseStatus;

    private String headline;
    private String summary;
    private String location;

    private List<String> skills;
    private List<CandidateProfileResponse.ExperienceEntry> experience;
    private List<CandidateProfileResponse.EducationEntry> education;

    /** Suggested target roles inferred from resume headline and experience titles. */
    private List<String> suggestedTargetRoles;

    /** Suggested work mode inferred from resume (remote mentions, etc.). */
    private String suggestedWorkMode;

    /** Confidence in structured extraction, not a judgment of resume quality. */
    private Integer parseConfidenceScore;
    private List<String> parseWarnings;
    private Double experienceYears;

    private LocalDateTime parsedAt;
}
