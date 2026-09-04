-- V25: API keys, so a person's AI agent can call AIRRAL as them.
--
-- A key is presented as `Authorization: Bearer airral_ak_live_<id>_<secret>`,
-- which SecurityContextRepository routes to the API-key path instead of the JWT
-- path. Both paths build the same principal, so no controller changes.

CREATE TABLE IF NOT EXISTS api_keys (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Copied at issuance rather than joined at request time. The role and
    -- organisation a key was granted under must not change under it because
    -- someone edited the user later: that would silently widen or narrow the
    -- reach of a credential already in someone's config file. NULL org is
    -- normal -- applicants have no organisation.
    organization_id BIGINT REFERENCES organizations(id) ON DELETE CASCADE,
    role            VARCHAR(50) NOT NULL,
    scopes          TEXT[]      NOT NULL,

    -- sha256 hex of the whole key, and the only copy of it that exists.
    -- SHA-256 rather than bcrypt on purpose: the cost factor in bcrypt exists
    -- to slow brute force against low-entropy human-chosen secrets, and the
    -- secret half of this key is 256 bits of CSPRNG output. There is nothing to
    -- brute force, so all bcrypt would buy is ~100ms of CPU on every single
    -- request -- which an agent makes dozens of per task.
    key_hash        CHAR(64)    NOT NULL UNIQUE,

    -- The public half. Safe in logs, shown in the UI, and how a key is named in
    -- an audit trail without ever revealing the secret.
    key_id          VARCHAR(16) NOT NULL UNIQUE,
    environment     VARCHAR(8)  NOT NULL DEFAULT 'live',

    name            VARCHAR(80) NOT NULL,
    issued_by       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    rate_per_minute INT         NOT NULL DEFAULT 60,

    last_used_at    TIMESTAMP,
    expires_at      TIMESTAMP,
    revoked_at      TIMESTAMP,
    revoked_reason  VARCHAR(160),
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT api_keys_environment_check CHECK (environment IN ('live', 'test')),
    CONSTRAINT api_keys_rate_check        CHECK (rate_per_minute > 0)
);

-- The hot path: one lookup per authenticated request. Partial, because a
-- revoked key must never be found -- which also keeps the index small.
CREATE INDEX IF NOT EXISTS idx_api_keys_lookup
    ON api_keys (key_hash)
    WHERE revoked_at IS NULL;

-- For the management screen: a user's keys, newest first.
CREATE INDEX IF NOT EXISTS idx_api_keys_user
    ON api_keys (user_id, created_at DESC);

COMMENT ON COLUMN api_keys.key_hash IS
    'sha256 hex of the full key. The raw key is shown once at issuance and never stored.';
COMMENT ON COLUMN api_keys.scopes IS
    'Derived from role at issuance. May be narrower than the role allows, never wider.';


-- Fixed-window rate counter.
--
-- In Postgres rather than in memory because Cloud Run runs up to 5 instances,
-- and 5 independent in-memory buckets is a 5x limit -- the kind of bug that
-- only shows up once someone is actually hammering the API.
CREATE TABLE IF NOT EXISTS api_key_usage (
    key_id       BIGINT    NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    window_start TIMESTAMP NOT NULL,
    calls        INT       NOT NULL DEFAULT 0,
    PRIMARY KEY (key_id, window_start)
);

-- Supports the nightly delete of old windows without scanning the table.
CREATE INDEX IF NOT EXISTS idx_api_key_usage_window
    ON api_key_usage (window_start);
