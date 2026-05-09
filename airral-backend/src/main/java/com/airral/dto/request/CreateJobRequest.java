package com.airral.dto.request;

import com.airral.domain.enums.JobStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateJobRequest {

    @NotBlank(message = "Job title is required")
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @NotBlank(message = "Job description is required")
    private String description;

    private Long departmentId;
    private String department;
    private String location;
    private String employmentType;

    // Salary range
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String currency;

    // Requirements
    private String requirements;
    private String niceToHave;

    // Status
    private JobStatus status;

    // ATS Configuration
    private List<String> atsKeywords;
    private String atsWeights; // JSON string
    private Integer atsMinScore;

    // LinkedIn Integration
    private Boolean linkedInEnabled;
}
