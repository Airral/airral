package com.airral.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReferralRequest {

    @NotBlank(message = "Referred person name is required")
    private String referredName;

    @NotBlank(message = "Referred person email is required")
    @Email(message = "Email must be valid")
    private String referredEmail;

    private String referredPhone;
    private Long jobId;
    private String notes;
    private String relationship;
    private String resumeUrl;
}
