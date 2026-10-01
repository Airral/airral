-- A key someone makes for themselves records the session it was made from.
--
-- Revoking every session (password reset, sign out everywhere, role change,
-- deactivation) bumps users.token_version. Each instance caches that version
-- for a short while, so a stolen session could still reach another instance
-- in the seconds after a revoke and make a fresh key -- one the owner's
-- recovery had already run past. A self-made key now carries the token
-- version of the session that made it, and resolves only while that version
-- is still current, so such a key is dead on arrival.
--
-- NULL for keys an admin issued: those are not made from the owner's session.
-- Non-NULL also marks a key as self-made, which is how its paid-feature
-- entitlement is checked on every request.
ALTER TABLE api_keys ADD COLUMN IF NOT EXISTS session_version INT;

COMMENT ON COLUMN api_keys.session_version IS
    'Token version of the session that made a self-service key; NULL when an admin issued it.';
