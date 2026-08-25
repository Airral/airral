-- V16: Candidate notification preferences for email re-engagement
-- Supports: job alerts, follow-up reminders, weekly digest, resume nudge

CREATE TABLE IF NOT EXISTS candidate_notification_preferences (
    id                             BIGSERIAL PRIMARY KEY,
    user_id                        BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Email notification toggles (opt-in by default)
    job_alert_enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    follow_up_reminder_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    weekly_digest_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    resume_nudge_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    saved_job_change_enabled       BOOLEAN NOT NULL DEFAULT TRUE,

    -- Last sent timestamps to prevent spamming
    last_job_alert_sent_at         TIMESTAMPTZ,
    last_follow_up_reminder_sent_at TIMESTAMPTZ,
    last_weekly_digest_sent_at     TIMESTAMPTZ,
    last_resume_nudge_sent_at      TIMESTAMPTZ,

    -- One-click unsubscribe token
    unsubscribe_token              VARCHAR(255) NOT NULL,

    created_at                     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_notification_pref_user UNIQUE (user_id),
    CONSTRAINT uq_notification_pref_token UNIQUE (unsubscribe_token)
);

CREATE INDEX IF NOT EXISTS idx_notification_pref_user_id ON candidate_notification_preferences(user_id);
CREATE INDEX IF NOT EXISTS idx_notification_pref_unsubscribe_token ON candidate_notification_preferences(unsubscribe_token);
