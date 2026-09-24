-- V36: Prove email ownership at sign-up, and stop unproven employers reaching
-- candidates.
--
-- Before this, /api/auth/register stored email_verified = false for every
-- password sign-up, signed the caller straight in, and nothing ever set the flag
-- -- no verification mail existed. Anyone could register an address they did not
-- own. For an employer that went further: the account came with an organization
-- whose name and domain the caller typed, and an OPEN job was projected into the
-- public candidate catalogue the moment it was saved. AIRRAL now proves the
-- address with a Firebase email link (FirebaseIdentityService) and holds jobs
-- back until the company is verified.

-- 1. One account per address, whatever its capitalisation.
--
-- users.email was UNIQUE case-sensitively, so Bob@x.com and bob@x.com were two
-- accounts -- and Google sign-in lowercases the address it is given while
-- password sign-up stored what was typed, so the same person could end up with
-- both. Refuse to guess which of two colliding rows is the real one: if any exist
-- the migration stops and names them, and a human merges them.
DO $$
DECLARE
    collisions TEXT;
BEGIN
    SELECT string_agg(lower(trim(email)) || ' (' || n || ' rows)', ', ')
      INTO collisions
      FROM (SELECT lower(trim(email)) AS email, count(*) AS n
              FROM users GROUP BY lower(trim(email)) HAVING count(*) > 1) dup;
    IF collisions IS NOT NULL THEN
        RAISE EXCEPTION 'V36: users.email differs only by case or whitespace for: %. Merge these accounts before migrating.', collisions;
    END IF;
END $$;

UPDATE users SET email = lower(trim(email)) WHERE email <> lower(trim(email));

CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_key ON users (lower(email));

-- 2. When, and how, an address was proven.
--
-- email_verified_at records when ownership was shown. password_proven_at records
-- that the password on the account was set by someone who had already shown it
-- -- through a reset link. The difference matters for linking Google: a row whose
-- password was typed at sign-up, before anyone proved the address, may belong to
-- whoever typed it first, even after the real owner later clicks a verification
-- link. AuthService only lets a Google sign-in adopt an existing password account
-- once its password is proven, not merely its address.
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_proven_at TIMESTAMP;

-- Google-created and Google-linked accounts are already verified by Google.
UPDATE users SET email_verified_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP)
 WHERE email_verified IS TRUE AND email_verified_at IS NULL;

-- 3. Company verification. A company's jobs reach candidates only when VERIFIED.
--
--   PENDING   the default, and where every existing company starts: none was
--             ever proven.
--   VERIFIED  by DOMAIN -- its HR manager proved an address on the company's own
--             non-free-mail domain -- or by ADMIN review.
--   REJECTED  an admin looked and said no.
--
-- Checked in InternalJobCatalogProjectionService, the one place a job is copied
-- into the public catalogue, so create, update and the five-minute reconciler are
-- all covered by the same rule.
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS verification_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS verification_method VARCHAR(16);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS verification_note TEXT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'organizations_verification_status_check') THEN
        ALTER TABLE organizations ADD CONSTRAINT organizations_verification_status_check
            CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_organizations_verification_status ON organizations (verification_status);

-- organizations.domain was UNIQUE outright, which did two wrong things. It turned
-- a second sign-up from the same company into a 500, and it let whoever signed
-- up first with an address on a domain -- ceo@stripe.com, never verified, never
-- verifiable by them -- hold that domain forever, locking the real company out.
-- Uniqueness only means something among companies that have proven the domain.
ALTER TABLE organizations DROP CONSTRAINT IF EXISTS organizations_domain_key;
CREATE UNIQUE INDEX IF NOT EXISTS organizations_verified_domain_key
    ON organizations (lower(domain))
    WHERE verification_status = 'VERIFIED' AND domain IS NOT NULL;

-- Take any employer job already in the public catalogue back out. Every company
-- is PENDING now, and the projection will re-publish a job as soon as its company
-- is verified. The source row is left; only the postings are deactivated.
UPDATE external_job_postings
   SET is_active = false, deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
 WHERE source_type = 'AIRRAL_INTERNAL' AND is_active = true;

-- 4. V35's reset-token table. Password reset now proves the address through the
-- same Firebase link as sign-up, so AIRRAL no longer mints or stores reset tokens
-- of its own. The table never held a token that reached anyone: nothing could
-- send the email.
DROP TABLE IF EXISTS password_reset_tokens;
