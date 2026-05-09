package com.airral.domain;

import com.airral.domain.enums.UserRole;
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
@Table("user_invitations")
public class UserInvitation {

    @Id
    private Long id;

    // Who invited
    private Long invitedById;
    private Long organizationId;

    // Invite details
    private String email;
    private UserRole role;
    private Long departmentId;
    private String firstName;
    private String lastName;
    private String department;

    // Invite token & expiry
    private String invitationToken;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;

    // Status
    private Boolean isAccepted;

    // Timestamp
    private LocalDateTime createdAt;

    // Helper methods
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }

    public boolean isAccepted() {
        return Boolean.TRUE.equals(isAccepted);
    }

    public boolean isValid() {
        return !isExpired() && !isAccepted();
    }
}
