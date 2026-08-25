-- V21: Enrich search_vector with tags and description for skill-based job matching
-- The original search_vector only indexed title, department, location, employment_type, source_name.
-- Resume-parsed skills (e.g., "Python", "Kafka", "Snowflake") live in tags and description_text,
-- so skill-based retrieval queries returned no results.

-- Rebuild search_vector to include tags and a truncated description excerpt
UPDATE external_job_postings SET search_vector =
    to_tsvector('english',
        COALESCE(title, '') || ' ' ||
        COALESCE(department, '') || ' ' ||
        COALESCE(location, '') || ' ' ||
        COALESCE(employment_type, '') || ' ' ||
        COALESCE(source_name, '') || ' ' ||
        COALESCE(array_to_string(tags, ' '), '') || ' ' ||
        COALESCE(LEFT(description_text, 2000), '')
    );

-- The GIN index on search_vector (from V18) will be auto-updated.
