-- V33: Two follow-ups to V32, both found by attacking V32 rather than admiring it.
--
-- 1. V32's feed index included last_seen_at as its fourth key. The upsert writes
--    last_seen_at = EXCLUDED.last_seen_at on every sighting of every posting
--    (ExternalJobPostingStore.java:1093), so all ~45,000 rows have that column
--    rewritten every four hours. A column that is indexed cannot take part in a
--    HOT update, so V32 would have turned the entire catalogue's four-hourly
--    refresh into non-HOT updates -- index churn and heap bloat, forever, to
--    serve a fourth-level tiebreak.
--
--    Dropping the key costs nothing measurable. The index still provides the
--    first three sort keys and an Incremental Sort resolves last_seen_at within
--    each tiny group: 102 buffers against V32's 122, on 49,118 rows locally.
--    The same reasoning is already written down for expires_at in the upsert's
--    own comment at ExternalJobPostingStore.java:1082-1088.
--
-- 2. GIN fastupdate is on by default, which buffers new entries in a pending list
--    that every search scans linearly until a vacuum merges it. That is a sane
--    default for trickle writes and a poor one here: each sync upserts ~45,000
--    rows in one burst and then the next search pays for it. Turning it off makes
--    the sync's index maintenance more expensive and keeps search cost flat, which
--    is the right way round -- nobody is waiting on the sync.
--
--    This is a targeted guess at the production symptom still outstanding after
--    V32, not a measured fix: after V32 the feed went 16s -> 0.26s and ?q=engineer
--    120s+ -> 0.46s, but ?q=nurse and ?q=warehouse were still 46s and 15s while
--    both are under 1,100 buffers locally. The pending list and stale statistics
--    are the two candidates that fit "slow in production, fast locally, improving
--    on its own". The ANALYZE below addresses the second.

DROP INDEX IF EXISTS idx_ejp_feed_day_quality;

CREATE INDEX IF NOT EXISTS idx_ejp_feed_day_quality
    ON external_job_postings (
        (((source_updated_at AT TIME ZONE 'UTC')::date)) DESC NULLS LAST,
        job_quality_score DESC NULLS LAST,
        source_updated_at DESC NULLS LAST
    )
    WHERE is_active = true;

ALTER INDEX idx_ejp_search_vector SET (fastupdate = off);

-- The catalogue went from 18,009 rows to 45,397 in one run and V32 added two
-- indexes the planner had never costed. ANALYZE runs inside a transaction, so
-- Flyway can carry it; VACUUM cannot, and is left to autovacuum.
ANALYZE external_job_postings;
