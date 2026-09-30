package com.airral.dto.response;

import com.airral.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An invitation as HR sees it. It never carries the token: that travels only in
 * the invitation email, and following the email's link is what proves the
 * person accepting it owns the address.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitationResponse {
    private Long id;
    private String email;
    private UserRole role;
    private String firstName;
    private String lastName;
    private String department;
    private LocalDateTime expiresAt;
    /** Past its date and not accepted: HR can resend it for a fresh week, or cancel it. */
    private Boolean expired;
    private LocalDateTime createdAt;
    /** Whether the invitation email went out this time; null when nothing was sent. */
    private Boolean emailSent;
}
