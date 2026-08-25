package com.airral.domain;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("candidate_job_fit_results")
public class CandidateJobFitResult {

    @Id
    private Long id;

    private Long userId;
    private String sourceJobKey;
    private Long resumeDocumentId;
    private Integer fitScore;
    private Integer visaFitScore;
    private Json matchedRequirements;
    private Json missingRequirements;
    private Json keywordGaps;
    private Json weakBullets;
    private Json suggestedRewrites;
    private Json applicationChecklist;
    private OffsetDateTime generatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
