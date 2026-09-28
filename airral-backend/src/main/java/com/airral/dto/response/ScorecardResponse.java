package com.airral.dto.response;

import com.airral.dto.interview.KitQuestion;
import com.airral.dto.interview.ScoreRating;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/** One interviewer's scorecard, with what it is about. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScorecardResponse {
    /** Null until the interviewer first saves it. */
    private Long id;
    private Long interviewId;
    private Long applicationId;
    private Long interviewerId;
    private String interviewerName;

    private String candidateName;
    private String jobTitle;
    private LocalDateTime interviewDate;
    private Integer durationMinutes;
    private String timeZone;

    /** The job's interview kit, or null when the job has none and the standard criteria apply. */
    private String kitName;
    private List<KitQuestion> questions;

    private List<ScoreRating> ratings;
    private String overallNotes;
    private String recommendation;
    /** DRAFT or SUBMITTED. */
    private String status;
    private LocalDateTime submittedAt;
    /** The weighted average of the ratings given, 1 to 5, or null before any. */
    private Double weightedScore;
}
