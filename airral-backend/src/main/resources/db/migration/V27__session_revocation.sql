-- V27: Make sessions revocable.
--
-- Session tokens are stateless JWEs with a 24-hour life. Nothing was stored, so
-- nothing could be deleted: a stolen token stayed valid for up to a day no
-- matter what, changing a password did not invalidate it, and there was no
-- sign-out-everywhere. API keys got this right by being a row; sessions did not.
--
-- A version number on the user, carried as a claim and compared on validation,
-- makes every outstanding token for that person invalid the moment it changes.
-- One column rather than a session table, because the tokens themselves already
-- carry identity -- all that was missing was something to compare against.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.token_version IS
    'Incrementing this invalidates every outstanding session token for the user. Bumped on password change and on explicit sign-out-everywhere.';
