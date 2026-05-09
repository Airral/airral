package com.airral.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEncounterRequest {

    @NotBlank(message = "Encounter type is required")
    private String encounterType;

    @NotBlank(message = "Title is required")
    private String title;

    private String description;
    private String notes;

    // Related entities
    @NotNull(message = "Application ID is required")
    private Long applicationId;
    
    private Long jobId;
    private Long candidateId;
    private Long interviewId;
    private Long offerId;

    // Assessment
    private String outcome; // POSITIVE, NEGATIVE, NEUTRAL, PENDING
    private Integer rating; // 1-5
    private String recommendation; // HIRE, NO_HIRE, HOLD
    private String priority; // LOW, MEDIUM, HIGH

    private String metadata; // JSON string
}
