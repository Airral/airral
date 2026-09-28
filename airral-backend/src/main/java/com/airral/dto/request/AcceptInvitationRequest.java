package com.airral.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Accepting an invitation: proof of the address, and the new account's password. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptInvitationRequest {

    /** The Firebase ID token the browser holds after following the invitation email's link. */
    @NotBlank(message = "A verification token is required")
    @Size(max = 4096, message = "Verification token is invalid")
    private String idToken;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 200, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$",
             message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
    private String password;

    @Size(max = 100, message = "First name must be at most 100 characters")
    private String firstName;

    @Size(max = 100, message = "Last name must be at most 100 characters")
    private String lastName;
}
