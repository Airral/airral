package com.airral.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class CandidateSavedJobResponse {
    private Long id;
    private String sourceJobKey;
    private String status;
    private Long resumeDocumentId;
    private Long fitResultId;
    private String nextStep;
    private OffsetDateTime nextStepDueAt;
    private String notes;
    private CandidateJobSummaryResponse job;
    private CandidateJobFitResponse fitResult;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
