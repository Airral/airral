package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EncounterResponse {

    private Long id;
    private Long organizationId;

    // Event details
    private String encounterType;
    private String title;
    private String description;
    private String notes;

    // Related entities
    private Long applicationId;
    private String candidateName;
    private Long jobId;
    private String jobTitle;
    private Long candidateId;
    private Long performedById;
    private String performedByName;
    private Long interviewId;
    private Long offerId;

    // Assessment
    private String outcome;
    private Integer rating;
    private String recommendation;
    private String priority;

    // Timestamps
    private LocalDateTime encounteredAt;
    private LocalDateTime createdAt;

    // Metadata
    private String metadata;
}
