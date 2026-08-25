package com.airral.dto.request;

import lombok.Data;

@Data
public class CandidateJobFitRequest {
    private String sourceJobKey;
    private Long resumeDocumentId;
}
