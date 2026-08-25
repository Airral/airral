package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * CandidateProfile — rich applicant profile separate from core User identity.
 * One record per APPLICANT user, auto-created on first portal load.
 * skills / experience / education are stored as JSONB in Postgres.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("candidate_profiles")
public class CandidateProfile {

    @Id
    private Long id;

    private Long userId;

    // Public-facing identity
    private String headline;
    private String bio;
    private String avatarUrl;
    private String location;

    // JSONB arrays / object
    // skills:     ["Java","Spring Boot","React"]
    // experience: [{"company":"...","title":"...","startDate":"...","endDate":"...","description":"..."}]
    // education:  [{"school":"...","degree":"...","field":"...","graduationYear":2021}]
    private Json skills;
    private Json experience;
    private Json education;
    private Json matchPreferences;

    // Media
    private Long activeResumeDocumentId;
    private String resumeUrl;
    private String resumeParseStatus;
    private LocalDateTime resumeParsedAt;
    private String videoIntroUrl;

    // Computed 0-100
    private Integer profileCompletion;

    // Open-to-work preferences
    private Boolean openToWork;
    private String preferredEmploymentType;
    private String preferredWorkMode;
    private BigDecimal salaryExpectationMin;
    private BigDecimal salaryExpectationMax;
    private String salaryCurrency;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
