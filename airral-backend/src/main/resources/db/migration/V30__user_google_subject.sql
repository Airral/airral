-- V30: Record which Google account a user row belongs to.
--
-- Google sign-in matched an existing account on the address inside the
-- credential and on nothing else, which would let one person's row absorb
-- another person's Google identity. /api/auth/register is public and creates
-- applicants with email_verified false, and no verification mail is ever
-- sent -- SMTP does not work from Cloud Run here -- so an unverified row is
-- evidence that somebody typed an address, not that they own it. Register
-- victim@example.com with a password of your choosing, wait for the real owner
-- to click "Continue with Google", and the platform hands them your row: your
-- password still opens their resume, applications and profile. The same shape
-- reaches ADMIN, because BootstrapAdminInitializer promotes whichever row holds
-- the bootstrap address on the next boot.
--
-- Not yet exploited, and the tense matters if anyone goes looking for damage:
-- POST /api/auth/google had no handler until the change this migration ships
-- with, so the route answered 404 and the address-only match never ran against
-- production. This closes the hole in the same deploy that opens the route.
--
-- Google's "sub" is what an address is not: a stable per-account identifier that
-- only Google can assert, and that survives the user renaming their address.
-- Storing it turns the link into a fact recorded when the account was proved,
-- rather than a string comparison two parties can both win.
--
-- Nullable, because every existing row is a password account that has never
-- seen Google -- the route was unreachable, so there is nothing to backfill --
-- and null has to keep meaning "not linked".
--
-- Unique, because one Google identity spread over two rows is the same hijack
-- with the direction reversed. Postgres counts nulls as distinct, so every
-- unlinked row still fits under the constraint. Spelled as a unique index rather
-- than ALTER TABLE ... ADD CONSTRAINT because only the index form accepts
-- IF NOT EXISTS, and every statement in this directory is re-runnable.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS google_subject VARCHAR(255);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_google_subject
    ON users (google_subject);

COMMENT ON COLUMN users.google_subject IS
    'Google''s "sub" claim for the linked Google account; null when the account has never been linked. Only ever set on a row that has already proved the address, because matching a Google credential on the address alone would let a pre-registered account collect somebody else''s sign-in.';
