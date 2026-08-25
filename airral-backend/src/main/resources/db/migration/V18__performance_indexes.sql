-- V18: Performance indexes for scaling to 50K+ jobs
-- Optimizes the main paginated job feed query and text search

-- 1. Composite index for the main feed query:
--    WHERE is_active = true AND expires_at > NOW()
--    ORDER BY source_updated_at DESC
CREATE INDEX IF NOT EXISTS idx_ejp_active_feed
    ON external_job_postings (is_active, expires_at, source_updated_at DESC NULLS LAST, match_score DESC NULLS LAST);

-- 2. Full-text search index using GIN + tsvector
--    Covers title, company name (via stored column), location, department
ALTER TABLE external_job_postings ADD COLUMN IF NOT EXISTS search_vector tsvector;

-- Populate search vector for existing rows
UPDATE external_job_postings SET search_vector =
    to_tsvector('english',
        COALESCE(title, '') || ' ' ||
        COALESCE(department, '') || ' ' ||
        COALESCE(location, '') || ' ' ||
        COALESCE(employment_type, '') || ' ' ||
        COALESCE(source_name, '')
    );

CREATE INDEX IF NOT EXISTS idx_ejp_search_vector
    ON external_job_postings USING GIN (search_vector);

-- 3. Index for source-type filtered queries
CREATE INDEX IF NOT EXISTS idx_ejp_source_type_active
    ON external_job_postings (source_type, is_active, source_updated_at DESC);

-- 4. Index for upsert lookups (sync uses source_type + board + external_id)
CREATE INDEX IF NOT EXISTS idx_ejp_source_board_extid
    ON external_job_postings (source_type, source_board_token, external_job_id);

-- 5. Index for expiry cleanup (scheduled job runs daily)
CREATE INDEX IF NOT EXISTS idx_ejp_expires_cleanup
    ON external_job_postings (expires_at, is_active);
