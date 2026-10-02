-- V49: An index for searching jobs by place.
--
-- A place search ("Denver", "Denver, CO") matches the city with
-- LOWER(location) LIKE '%denver%', and a leading wildcard has no btree answer, so
-- every such search read the whole table: ~17,000 blocks for any city, however
-- rare. Measured on Postgres 16 with production's 128MB of shared buffers:
--
--   search               without the index   with it
--   denver                     17,157          566 blocks
--   denver, co                 19,755        2,382
--   telluride                  17,157           43
--
-- The index is 4 MB. The state half of a search (its name, or its abbreviation
-- in capitals) is not served by it and does not need to be: a state matches
-- thousands of postings, so the search stops after the first page.
--
-- Same shape as V34's title index: trigram, partial to active postings, so the
-- retired postings the table keeps for a while cost it nothing. LOCATION is not
-- a column the sync changes for an unchanged posting, so this does not stop
-- those updates from being HOT (see V48).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_ejp_location_trgm
    ON external_job_postings USING gin (LOWER(location) gin_trgm_ops)
    WHERE is_active = true;
