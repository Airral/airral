-- V34: What the index-first search shape needs to exist and to be chosen.
--
-- The candidate search filters on three conditions joined by OR:
--   search_vector @@ plainto_tsquery(q)
--   OR LOWER(title) LIKE '%q%'
--   OR the employer's name LIKE '%q%'
-- LIKE with a leading wildcard has no btree answer, so the OR as a whole could
-- never be served from an index and every search walked the feed in order,
-- testing -- and detoasting the tsvector of -- each row it passed. Rare terms
-- walked most of the table: 'pharmacist' read 98,476 buffers for 51 rows, and
-- production's 'nurse' took 38 seconds. ExternalJobPostingStore now collects
-- matches through the indexes first when there are few of them; see
-- INDEX_FIRST_TEXT_MATCH_CEILING for the measurements behind the threshold.

-- pg_trgm is a trusted extension on PostgreSQL 13+, so the application role can
-- create it the same way V1 created uuid-ossp. It gives the LIKE arm an index.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_ejp_title_trgm
    ON external_job_postings USING gin (LOWER(title) gin_trgm_ops)
    WHERE is_active = true;

-- The choice between the two shapes is made from the planner's own row estimate,
-- so the estimate has to be right. At the default statistics target of 100 the
-- most-common-lexeme list is too short: 'nurse' was estimated at 246 rows against
-- 681 real matches. At 1000, every term measured -- nurse, warehouse, engineer,
-- pharmacist, driver, software, cashier, registered, accountant, welder --
-- estimated exactly its real count. ANALYZE is what makes the new target count.
ALTER TABLE external_job_postings ALTER COLUMN search_vector SET STATISTICS 1000;

ANALYZE external_job_postings;
