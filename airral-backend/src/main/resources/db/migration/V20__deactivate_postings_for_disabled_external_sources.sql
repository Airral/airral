-- V20: Keep cached postings aligned with source health.
-- V19 disables stale source records; this follow-up marks any postings from
-- inactive sources inactive too, without changing the already-applied V19 file.

UPDATE external_job_postings p
SET is_active = false,
    deleted_at = COALESCE(p.deleted_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
FROM external_job_sources s
WHERE p.job_source_id = s.id
  AND p.is_active = true
  AND s.is_active = false;
