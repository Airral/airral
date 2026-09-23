-- V35: Password reset.
--
-- There was no way back into an account whose password had been forgotten.
-- /api/auth had login, register, Google sign-in and revoke-sessions and nothing
-- else, so anyone who signed up with a password and lost it was locked out for
-- good. user_credentials (V1) has reset columns but was never wired to anything
-- and holds no rows; passwords live in users.password_hash, so this hangs off
-- users rather than reviving a table nothing reads.
--
-- Only a SHA-256 of the token is stored. The token itself exists in exactly one
-- place, the email, so a read of this table -- a backup, a log of a query, a
-- support session -- yields nothing that resets a password.
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    used_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Serves the per-account request limit (count in the last hour) and the
-- "invalidate every other outstanding link" step on a successful reset.
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_created
    ON password_reset_tokens (user_id, created_at DESC);
