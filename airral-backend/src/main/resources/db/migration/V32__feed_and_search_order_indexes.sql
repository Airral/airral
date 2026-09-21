-- V32: Make the candidate feed and the search path sortable from an index.
--
-- V31 took the catalogue from 18,009 rows to 45,397. Both of the queries a
-- candidate actually waits on were sorting the whole active set in memory, which
-- was survivable at 18k and is not at 45k on a db-f1-micro (0.6 GB RAM, shared
-- burstable vCPU). Measured against production after V31 deployed:
--
--   GET /api/candidate/jobs/recommended/page            16 s
--   the same with ?q=engineer                     timed out past 120 s
--
-- Buffers, not wall clock, are the honest measure here -- they are the same on
-- any hardware, and they are what a 0.6 GB instance cannot absorb. Measured
-- locally at 49,118 rows with max_parallel_workers_per_gather = 0:
--
--   feed    ORDER BY DATE_TRUNC(...)      11,755 buffers  ->    122   (96x)
--   search  ORDER BY source_updated_at   169,706 buffers  ->  4,138   (41x)
--
-- The search figure is so large because search_vector is TOASTed: a sequential
-- scan detoasts every row's tsvector, so it moved about 1.3 GB to return 50 rows.

-- The feed's sort key. DATE_TRUNC('day', timestamptz) is STABLE, not IMMUTABLE --
-- its result depends on the session TimeZone -- so it can never be indexed. The
-- UTC-pinned equivalent is immutable and indexes fine.
--
-- Production runs UTC (Cloud SQL default; the instance sets no timezone flag), so
-- under production's own settings these two expressions are the same function and
-- the feed order does not change: the top 50 ids were identical before and after,
-- checked both under UTC and under the local America/New_York session. Pinning it
-- also removes a latent inconsistency -- the local database bucketed rows into New
-- York days and production into UTC days, from the same code.
--
-- expires_at > CURRENT_TIMESTAMP cannot be a partial predicate (not immutable), so
-- it stays a filter applied over the index scan. That is fine: nearly every active
-- row is unexpired, so LIMIT 50 stops after roughly 50 index entries.
CREATE INDEX IF NOT EXISTS idx_ejp_feed_day_quality
    ON external_job_postings (
        ((source_updated_at AT TIME ZONE 'UTC')::date) DESC NULLS LAST,
        job_quality_score DESC NULLS LAST,
        source_updated_at DESC NULLS LAST,
        last_seen_at DESC
    )
    WHERE is_active = true;

-- The search path orders by plain recency and filters on the tsvector. For a term
-- matching a large share of the corpus the planner will not use the GIN index --
-- 'engineer' matches 39% of rows -- so it was scanning and detoasting everything.
-- Walking recency order and testing the filter as it goes finds 50 matches almost
-- immediately for exactly those common terms.
--
-- The opposite case is the one to watch: for a RARE term this index would walk the
-- whole table to find a handful of rows. It does not, because the planner still
-- prefers GIN when the term is selective. Verified on real data with this index
-- present: 'engineer' 4,127 buffers via this index, 'phlebotomist' 60 buffers via
-- idx_ejp_search_vector, a term matching nothing 5 buffers.
CREATE INDEX IF NOT EXISTS idx_ejp_active_recency
    ON external_job_postings (source_updated_at DESC NULLS LAST)
    WHERE is_active = true;
