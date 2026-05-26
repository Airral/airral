package com.airral.dto.request;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdateCandidateProfileRequest {

    private String headline;
    private String bio;
    private String avatarUrl;
    private String location;

    // Sent as arrays from frontend; serialized to JSON for DB
    private List<String> skills;
    private List<ExperienceEntry> experience;
    private List<EducationEntry> education;
    private MatchPreferences matchPreferences;

    private String resumeUrl;
    private String videoIntroUrl;

    private Boolean openToWork;
    private String preferredEmploymentType;
    private String preferredWorkMode;
    private BigDecimal salaryExpectationMin;
    private BigDecimal salaryExpectationMax;
    private String salaryCurrency;

    @Data
    public static class ExperienceEntry {
        private String company;
        private String title;
        private String startDate;
        private String endDate;
        private String description;
        private Boolean current;
    }

    @Data
    public static class EducationEntry {
        private String school;
        private String degree;
        private String field;
        private Integer graduationYear;
    }

    @Data
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
