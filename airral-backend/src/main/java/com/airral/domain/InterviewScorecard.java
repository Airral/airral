package com.airral.domain;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** One interviewer's scorecard for one interview. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("interview_scorecards")
public class InterviewScorecard {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";

    @Id
    private Long id;
    private Long interviewId;
    private Long interviewerId;
    /** [{"criterion", "category", "weight", "rating", "notes"}] */
    private Json ratings;
    private String overallNotes;
    /** STRONG_HIRE, HIRE, NO_HIRE or STRONG_NO_HIRE. */
    private String recommendation;
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
