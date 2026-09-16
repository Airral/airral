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

    /**
     * Whether anything is actually sending these emails right now.
     *
     * <p>Read from {@code airral.notifications.scheduler.enabled}, which gates
     * the whole scheduler with {@code @ConditionalOnProperty}. It defaults to
     * false and is not set on the deployed service, so today no notification
     * email is sent at all -- the page offered five switches over a delivery
     * path that does not run.
     *
     * <p>Carried in the response rather than assumed by the portal so the notice
     * disappears on its own when the property is turned on, instead of becoming
     * a stale claim in the other direction.
     */
    private Boolean emailDeliveryActive;
}
