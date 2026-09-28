-- The person responsible for filling a job. A hiring manager sees and
-- interviews the candidates for the jobs they are hiring manager on, and only
-- those.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS hiring_manager_id BIGINT REFERENCES users(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_jobs_hiring_manager ON jobs (organization_id, hiring_manager_id);
