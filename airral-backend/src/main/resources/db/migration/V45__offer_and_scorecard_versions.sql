-- Offers and scorecards are saved whole, so two saves racing each other could
-- each overwrite the other: an offer withdrawn while the candidate accepted
-- it, or a draft saved in one tab over a scorecard submitted in another. With
-- a version column, the second save fails instead.
ALTER TABLE offers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE interview_scorecards ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- A candidate has one open offer at a time. That was checked before saving,
-- and two requests at once could both pass the check. Before the database
-- can hold the rule, an application with more than one open offer keeps its
-- newest; the others are withdrawn, or expired when past their date.
UPDATE offers o
SET status = CASE WHEN o.status = 'SENT' AND o.expires_at < CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE 'WITHDRAWN' END,
    updated_at = CURRENT_TIMESTAMP
WHERE o.status IN ('DRAFT', 'SENT')
  AND EXISTS (
      SELECT 1 FROM offers newer
      WHERE newer.application_id = o.application_id
        AND newer.status IN ('DRAFT', 'SENT')
        AND newer.id > o.id
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_offers_one_open_per_application
    ON offers (application_id) WHERE status IN ('DRAFT', 'SENT');
