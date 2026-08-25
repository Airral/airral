package com.airral.domain;

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
@Table("candidate_saved_jobs")
public class CandidateSavedJob {

    @Id
    private Long id;

    private Long userId;
    private String sourceJobKey;
    private String status;
    private Long resumeDocumentId;
    private Long fitResultId;
    private String nextStep;
    private OffsetDateTime nextStepDueAt;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
