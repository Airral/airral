-- V12: Feed safety controls.
-- Public social surfaces need moderation state and abuse counters before the
-- applicant feed becomes a real product surface.

ALTER TABLE feed_posts
    ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(30) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN IF NOT EXISTS report_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS hidden_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_feed_posts_public_approved
    ON feed_posts(visibility, moderation_status, published_at DESC)
    WHERE visibility = 'PUBLIC' AND moderation_status = 'APPROVED';

CREATE INDEX IF NOT EXISTS idx_feed_posts_author_recent
    ON feed_posts(author_id, author_type, created_at DESC);
