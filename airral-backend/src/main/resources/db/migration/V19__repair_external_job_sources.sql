-- V19: Repair stale public ATS source seeds.
-- The scheduled sync must only spend time on public boards that still exist.
-- These sources were checked after the 2026-06-17 sync failures.

-- Disable source records that currently return 404 from the public ATS APIs.
UPDATE external_job_sources
SET is_active = false,
    last_synced_at = CURRENT_TIMESTAMP,
    last_error = 'Disabled by V19: public Greenhouse board returned 404 during source repair',
    updated_at = CURRENT_TIMESTAMP
WHERE source_type = 'GREENHOUSE'
  AND board_token IN (
      'dbtlabs',
      'stytch',
      'supabase',
      'synthesia',
      'tabnine',
      'temporal',
      'tempus',
      'tinybird',
      'twosigma',
      'unit',
      'vanta',
      'veeva',
      'wandb',
      'wayfair',
      'weaviate',
      'wiz',
      'workos',
      'wpengine',
      'writer',
      'zapier',
      'zed',
      'zillow',
      'ziphq'
  );

UPDATE external_job_sources
SET is_active = false,
    last_synced_at = CURRENT_TIMESTAMP,
    last_error = 'Disabled by V19: public Lever site returned 404 during source repair',
    updated_at = CURRENT_TIMESTAMP
WHERE source_type = 'LEVER'
  AND board_token IN ('stainlessapi', 'triggerdev', 'turso');

UPDATE external_job_sources
SET is_active = false,
    last_synced_at = CURRENT_TIMESTAMP,
    last_error = 'Disabled by V19: public Ashby board returned 404 during source repair',
    updated_at = CURRENT_TIMESTAMP
WHERE source_type = 'ASHBY'
  AND board_token IN ('Truewind', 'Unriddle');

-- Seed verified replacement Ashby boards for companies that moved away from
-- Greenhouse/Lever. Existing rows are reactivated because these were checked
-- against Ashby's public posting API during this repair.
DO $$
DECLARE
    r RECORD;
    cid BIGINT;
BEGIN
    FOR r IN
        SELECT * FROM (VALUES
            ('stytch', 'Stytch'),
            ('supabase', 'Supabase'),
            ('synthesia', 'Synthesia'),
            ('temporal', 'Temporal'),
            ('trigger-dev', 'TriggerDev'),
            ('unit', 'Unit'),
            ('vanta', 'Vanta'),
            ('weaviate', 'Weaviate'),
            ('wiz', 'Wiz'),
            ('workos', 'WorkOS'),
            ('writer', 'Writer'),
            ('zapier', 'Zapier'),
            ('zed', 'Zed')
        ) AS t(normalized_name, board_token)
    LOOP
        SELECT id INTO cid FROM external_companies WHERE normalized_name = r.normalized_name;
        IF cid IS NOT NULL THEN
            INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
            VALUES (cid, 'ASHBY', r.board_token, 'Ashby')
            ON CONFLICT (source_type, board_token) DO UPDATE SET
                source_name = 'Ashby',
                is_active = true,
                last_error = NULL,
                updated_at = CURRENT_TIMESTAMP;
        END IF;
    END LOOP;
END $$;
