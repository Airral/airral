package com.airral.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateNotificationPreferencesRequest {
    private Boolean jobAlertEnabled;
    private Boolean followUpReminderEnabled;
    private Boolean weeklyDigestEnabled;
    private Boolean resumeNudgeEnabled;
    private Boolean savedJobChangeEnabled;
}
