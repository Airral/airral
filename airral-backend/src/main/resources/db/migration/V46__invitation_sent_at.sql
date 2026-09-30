-- When an invitation's email went out. NULL means it has not: the company is
-- still waiting for AIRRAL's review, and invitations are held until it is
-- approved, so a company nobody has checked cannot use AIRRAL's address to
-- email strangers. Approval sends the held ones.
--
-- Every invitation before this one was emailed when it was made (or its send
-- failed and HR had Resend), so they count as sent.
ALTER TABLE user_invitations ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP;
UPDATE user_invitations SET sent_at = COALESCE(created_at, CURRENT_TIMESTAMP) WHERE sent_at IS NULL;
