-- V26: Rate limiting for sign-in.
--
-- /api/auth/login was public and unthrottled: no attempt counter, no lockout,
-- no backoff. Passwords could be guessed as fast as the API would answer, and
-- the platform admin account is one password away from everything.
--
-- Same fixed-window shape as api_key_usage, and in Postgres for the same
-- reason: Cloud Run runs up to five instances, and five in-memory counters is
-- a five-fold limit -- which for a lockout means five times as many guesses as
-- intended.

CREATE TABLE IF NOT EXISTS auth_attempt_windows (
    -- Prefixed so the two kinds of bucket cannot collide: "email:a@b.com" and
    -- "ip:203.0.113.4". Both are counted for every attempt, because they catch
    -- different attacks -- one address hammered from many hosts, or many
    -- addresses sprayed from one.
    bucket_key   VARCHAR(200) NOT NULL,
    window_start TIMESTAMP    NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_key, window_start)
);

-- Supports the sweep of expired windows without scanning the table.
CREATE INDEX IF NOT EXISTS idx_auth_attempt_window
    ON auth_attempt_windows (window_start);

COMMENT ON TABLE auth_attempt_windows IS
    'Failed sign-in counters. Only failures are recorded: a correct password must not consume anyone''s budget.';
