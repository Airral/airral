package com.airral.dto.response;

import com.airral.domain.enums.ApplicationStatus;
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
public class ApplicationResponse {

    private Long id;
    private Long jobId;
    private String jobTitle;
    
    private Long applicantId;
    private String applicantName;
    private String applicantEmail;
    private String applicantPhone;
    
    private String resumeUrl;
    private String coverLetter;
    
    private ApplicationStatus status;
    
    // ATS Scoring
    private Integer atsScore;
    private List<String> atsMatchedKeywords;
    private List<String> atsMissingKeywords;
    private Boolean visibleToHr;
    
    private String reviewedBy;
    private LocalDateTime reviewedByHrAt;
    
    private LocalDateTime appliedAt;
    private LocalDateTime updatedAt;
}
