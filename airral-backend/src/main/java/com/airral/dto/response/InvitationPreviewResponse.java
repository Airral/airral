package com.airral.dto.response;

import com.airral.domain.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the accept page shows before the invitee sets a password. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvitationPreviewResponse {
    private String email;
    private String companyName;
    private UserRole role;
    private String firstName;
    private String lastName;
}
