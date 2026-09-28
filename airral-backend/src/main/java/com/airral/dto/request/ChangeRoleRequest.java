package com.airral.dto.request;

import com.airral.domain.enums.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A member's new role in their company. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangeRoleRequest {
    @NotNull(message = "Role is required")
    private UserRole role;
}
