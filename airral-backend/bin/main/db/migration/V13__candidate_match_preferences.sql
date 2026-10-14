-- V13: Store applicant matching intent separately from public profile fields.
-- Keeps richer matching signals structured without overloading headline or bio.

ALTER TABLE candidate_profiles
    ADD COLUMN IF NOT EXISTS match_preferences JSONB NOT NULL DEFAULT '{}'::jsonb;
