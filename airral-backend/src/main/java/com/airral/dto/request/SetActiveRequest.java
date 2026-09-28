package com.airral.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Whether a member's account may be used. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetActiveRequest {
    @NotNull(message = "Say whether the account is active")
    private Boolean active;
}
