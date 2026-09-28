package com.airral.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleInterviewRequest {

    @NotNull(message = "Application ID is required")
    private Long applicationId;

    @NotNull(message = "Interview date is required")
    private LocalDateTime interviewDate;

    private String notes;

    /** Email the candidate the day and time. Off unless the caller asks. */
    private Boolean notifyCandidate;

    /** Teammates who interview: HR managers, hiring managers or interviewers in the company. */
    @Size(max = 10, message = "An interview can have at most 10 interviewers")
    private List<Long> interviewerIds;

    @Min(value = 15, message = "An interview is at least 15 minutes")
    @Max(value = 480, message = "An interview is at most 8 hours")
    private Integer durationMinutes;

    /** The booker's IANA time zone, e.g. America/New_York. interviewDate is in it. */
    @Size(max = 64)
    private String timeZone;
}
