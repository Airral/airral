package com.airral.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CandidateJobFitResponse {
    private Long id;
    private String sourceJobKey;
    private Long resumeDocumentId;
    private Integer fitScore;
    private Integer visaFitScore;
    private List<String> matchedRequirements;
    private List<String> missingRequirements;
    private List<String> keywordGaps;
    private List<String> weakBullets;
    private List<String> suggestedRewrites;
    private List<String> applicationChecklist;
    private CandidateJobDetailResponse job;
    private OffsetDateTime generatedAt;
}
