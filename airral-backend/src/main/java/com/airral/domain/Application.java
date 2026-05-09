package com.airral.domain;

import com.airral.domain.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("applications")
public class Application {

    @Id
    private Long id;

    // Job relationship
    private Long jobId;

    // Applicant information
    private Long applicantId; // Reference to users table (can be null for external applicants)
    private String applicantName;
    private String applicantEmail;
    private String applicantPhone;

    // Application materials
    private String resumeUrl;
    private String resumeText; // Extracted text for ATS
    private String coverLetter;

    // Status
    private ApplicationStatus status;

    // ATS Scoring
    private Integer atsScore;
    private String atsMatchedKeywords; // TEXT[] in DB, stored as comma-separated
    private String atsMissingKeywords; // TEXT[] in DB
    private String atsMatchDetails; // JSONB in DB
    private Boolean visibleToHr;

    // Review tracking
    private Long reviewedByHrId;
    private LocalDateTime reviewedByHrAt;

    // Timestamps
    private LocalDateTime appliedAt;
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isNew() {
        return status == ApplicationStatus.SUBMITTED;
    }

    public boolean isActive() {
        return status != ApplicationStatus.REJECTED && 
               status != ApplicationStatus.WITHDRAWN &&
               status != ApplicationStatus.HIRED;
    }

    public boolean isVisibleToHr() {
        return Boolean.TRUE.equals(visibleToHr);
    }
}
