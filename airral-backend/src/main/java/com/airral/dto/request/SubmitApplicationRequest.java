package com.airral.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "Name must be at most 255 characters")
    private String applicantName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String applicantEmail;

    @Size(max = 50, message = "Phone must be at most 50 characters")
    private String applicantPhone;

    /** A link to the resume, for a candidate HR adds by hand. An applicant's own resume is attached from their profile. */
    @Size(max = 500, message = "Resume link must be at most 500 characters")
    @Pattern(regexp = "^(https?://\\S+)?$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "Resume link must start with https:// or http://")
    private String resumeUrl;

    @Size(max = 10000, message = "Cover letter must be at most 10,000 characters")
    private String coverLetter;

    // Optional: if applicant is logged in
    private Long applicantId;
}
