-- V37: which signed-in account an analytics event came from, when there is one.
--
-- Visits stay anonymous: most beacons come from people who are not signed in
-- and carry only the daily visitor key. When a signed-in applicant clicks
-- "Apply now", though, the launch funnel needs to know which account got that
-- far, or the last step of the funnel would be a count nobody can tie to a
-- sign-up. Nullable, and set null if the account is deleted.

ALTER TABLE analytics_events
    ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id) ON DELETE SET NULL;

-- The funnel asks "has this user ever sent this event", once per user.
CREATE INDEX IF NOT EXISTS idx_analytics_events_user
    ON analytics_events (user_id, event_name, created_at)
    WHERE user_id IS NOT NULL;
