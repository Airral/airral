package com.airral.domain;

import com.airral.domain.enums.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("jobs")
public class Job {

    @Id
    private Long id;

    // Multi-tenancy
    private Long organizationId;
    private Long createdById;

    // Job details
    private String title;
    private String description;
    private Long departmentId;
    private String department;
    private String location;
    private String employmentType; // Full-time, Part-time, Contract, etc.

    // Salary range
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String currency;

    // Requirements
    private String requirements;
    private String niceToHave;

    // Status
    private JobStatus status;

    // ATS Configuration (stored as comma-separated or JSON in DB)
    private String atsKeywords; // Will be stored as TEXT[] in PostgreSQL
    private String atsWeights; // JSONB in DB
    private Integer atsMinScore;

    // LinkedIn Integration
    private String linkedinPostId;
    private Boolean linkedInEnabled;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isActive() {
        return status == JobStatus.OPEN;
    }

    public boolean isDraft() {
        return status == JobStatus.DRAFT;
    }

    public boolean isClosed() {
        return status == JobStatus.CLOSED || status == JobStatus.FILLED;
    }

    public boolean isLinkedInEnabled() {
        return Boolean.TRUE.equals(linkedInEnabled);
    }
}
