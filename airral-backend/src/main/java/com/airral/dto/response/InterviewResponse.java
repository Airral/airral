package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewResponse {

    private Long id;
    private Long applicationId;
    private Long jobId;
    
    // Candidate info (from application)
    private String candidateName;
    private String candidateEmail;
    private String jobTitle;
    
    // Scheduling
    private String scheduledBy;
    private LocalDateTime interviewDate;
    private Integer durationMinutes;
    private String timeZone;
    private String status;

    /** The teammates interviewing. */
    private List<InterviewerSummary> interviewers;

    /** On My interviews only: the viewer's own scorecard, DRAFT or SUBMITTED, or null before they start one. */
    private String myScorecardStatus;
    
    // Feedback
    private String feedback;
    private Integer rating;
    private String notes;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
