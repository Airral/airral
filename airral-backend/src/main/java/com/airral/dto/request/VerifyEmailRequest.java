package com.airral.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The Firebase ID token the browser holds after following an email link. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyEmailRequest {

    @NotBlank(message = "A verification token is required")
    @Size(max = 4096, message = "Verification token is invalid")
    private String idToken;
}
