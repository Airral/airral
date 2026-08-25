package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferencesResponse {
    private Boolean jobAlertEnabled;
    private Boolean followUpReminderEnabled;
    private Boolean weeklyDigestEnabled;
    private Boolean resumeNudgeEnabled;
    private Boolean savedJobChangeEnabled;
}
