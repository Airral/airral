package com.airral.dto.request;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SaveCandidateJobRequest {
    private String sourceJobKey;
    private String status;
    private Long resumeDocumentId;
    private String nextStep;
    private OffsetDateTime nextStepDueAt;
    private String notes;
}
