package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewResponse {

    private Long id;
    private Long applicationId;
    
    // Candidate info (from application)
    private String candidateName;
    private String candidateEmail;
    private String jobTitle;
    
    // Scheduling
    private String scheduledBy;
    private LocalDateTime interviewDate;
    private String status;
    
    // Feedback
    private String feedback;
    private Integer rating;
    private String notes;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
