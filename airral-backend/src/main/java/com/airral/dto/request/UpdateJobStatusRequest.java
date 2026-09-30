package com.airral.dto.request;

import com.airral.domain.enums.JobStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A job's new status, and nothing else about it. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateJobStatusRequest {
    @NotNull(message = "Status is required")
    private JobStatus status;
}
