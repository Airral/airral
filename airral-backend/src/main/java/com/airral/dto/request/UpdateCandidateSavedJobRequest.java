package com.airral.dto.request;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class UpdateCandidateSavedJobRequest {
    private String status;
    private Long resumeDocumentId;
    private Long fitResultId;
    private String nextStep;
    private OffsetDateTime nextStepDueAt;
    private String notes;
}
