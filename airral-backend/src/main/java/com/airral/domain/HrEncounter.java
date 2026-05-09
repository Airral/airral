package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * HrEncounter - Captures every important touchpoint in the hiring pipeline
 * Provides audit trail, timeline, and activity history for candidates
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("hr_encounters")
public class HrEncounter {

    @Id
    private Long id;

    // Multi-tenant
    private Long organizationId;

    // What happened
    private String encounterType; // APPLICATION_SUBMITTED, REVIEWED, INTERVIEW_SCHEDULED, etc.
    private String title;
    private String description;
    private String notes;

    // Who was involved
    private Long applicationId; // FK to applications
    private Long jobId; // FK to jobs
    private Long candidateId; // FK to users (applicants)
    private Long performedById; // Who did the action
    private Long interviewId; // FK to interviews (if applicable)
    private Long offerId; // FK to offers (if applicable)

    // Metadata
    private String outcome; // POSITIVE, NEGATIVE, NEUTRAL, PENDING
    private Integer rating; // 1-5 rating if applicable
    private String recommendation; // HIRE, NO_HIRE, HOLD, STRONG_HIRE
    private String priority; // LOW, MEDIUM, HIGH, URGENT

    // Timestamps
    private LocalDateTime encounteredAt; // When the event happened
    private LocalDateTime createdAt; // When the record was created

    // Additional context
    private String metadata; // JSON for flexible data
}
