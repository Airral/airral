package com.airral.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitApplicationRequest {

    @NotNull(message = "Job ID is required")
    private Long jobId;

    @NotBlank(message = "Name is required")
    private String applicantName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String applicantEmail;

    private String applicantPhone;

    @NotBlank(message = "Resume URL is required")
    private String resumeUrl;

    private String coverLetter;

    // Optional: if applicant is logged in
    private Long applicantId;
}
