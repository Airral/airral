package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * Tracks notification preferences and delivery state per user.
 * Controls which email notifications a user receives and when the last one was sent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("candidate_notification_preferences")
public class CandidateNotificationPreference {

    @Id
    private Long id;

    private Long userId;

    // Email notification toggles
    private Boolean jobAlertEnabled;
    private Boolean followUpReminderEnabled;
    private Boolean weeklyDigestEnabled;
    private Boolean resumeNudgeEnabled;
    private Boolean savedJobChangeEnabled;

    // Last sent timestamps (prevent spamming)
    private OffsetDateTime lastJobAlertSentAt;
    private OffsetDateTime lastFollowUpReminderSentAt;
    private OffsetDateTime lastWeeklyDigestSentAt;
    private OffsetDateTime lastResumeNudgeSentAt;

    // Unsubscribe token for one-click unsubscribe
    private String unsubscribeToken;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
