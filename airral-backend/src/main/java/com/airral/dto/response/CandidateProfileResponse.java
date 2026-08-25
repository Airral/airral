package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CandidateProfileResponse {

    private Long id;
    private Long userId;
    private String email;
    private String firstName;
    private String lastName;

    // Rich profile fields
    private String headline;
    private String bio;
    private String avatarUrl;
    private String location;
    private List<String> skills;
    private List<ExperienceEntry> experience;
    private List<EducationEntry> education;
    private MatchPreferences matchPreferences;

    private Long activeResumeDocumentId;
    private String resumeUrl;
    private String resumeParseStatus;
    private LocalDateTime resumeParsedAt;
    private String videoIntroUrl;
    private Integer profileCompletion;

    // Open-to-work
    private Boolean openToWork;
    private String preferredEmploymentType;
    private String preferredWorkMode;
    private BigDecimal salaryExpectationMin;
    private BigDecimal salaryExpectationMax;
    private String salaryCurrency;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperienceEntry {
        private String company;
        private String title;
        private String startDate;
        private String endDate;
        private String description;
        private Boolean current;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EducationEntry {
        private String school;
        private String degree;
        private String field;
        private Integer graduationYear;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MatchPreferences {
        private List<String> targetRoles;
        private String seniority;
        private String searchStatus;
        private Boolean needsSponsorship;
        private Boolean openToRelocation;
        private Boolean salaryRequired;
        private Boolean easyApplyOnly;
        private Boolean noTakeHome;
        private Boolean directCompanySourceOnly;
        private Boolean stabilityFirst;
        private List<String> mustHaveSkills;
        private List<String> niceToHaveSkills;
        private List<String> avoidKeywords;
    }
}
