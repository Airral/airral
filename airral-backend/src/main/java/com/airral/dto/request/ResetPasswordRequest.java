package com.airral.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    /** The Firebase ID token the browser holds after following the reset email's link. */
    @NotBlank(message = "A verification token is required")
    @Size(max = 4096, message = "Verification token is invalid")
    private String idToken;

    // Same rule as RegisterRequest.password, so a reset cannot set a password
    // that sign-up would have refused.
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 200, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
             message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
    private String password;
}
