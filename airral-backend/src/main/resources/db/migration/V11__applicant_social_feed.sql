-- V11: Applicant-authored social feed posts.
-- Feed started as company-authored posts. AIRRAL also needs LinkedIn-like
-- applicant updates: career changes, job-search asks, interview notes,
-- referral offers, and salary intel. Keep these in the same feed surface,
-- but mark the author and target context explicitly.

ALTER TABLE feed_posts
    ALTER COLUMN organization_id DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS author_type VARCHAR(30) NOT NULL DEFAULT 'COMPANY',
    ADD COLUMN IF NOT EXISTS author_display_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS target_type VARCHAR(40),
    ADD COLUMN IF NOT EXISTS target_label VARCHAR(255),
    ADD COLUMN IF NOT EXISTS linked_external_job_key VARCHAR(900);

CREATE INDEX IF NOT EXISTS idx_feed_posts_author ON feed_posts(author_type, author_id);
CREATE INDEX IF NOT EXISTS idx_feed_posts_target ON feed_posts(target_type, target_label);
