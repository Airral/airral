-- V22: Add experience_years and seniority_label to external_job_postings
-- Stores the inferred years-of-experience requirement so the frontend can filter
-- without needing to re-parse titles/descriptions every request.

ALTER TABLE external_job_postings ADD COLUMN IF NOT EXISTS experience_years SMALLINT;
ALTER TABLE external_job_postings ADD COLUMN IF NOT EXISTS seniority_label VARCHAR(20);

-- Backfill seniority_label from title keywords for all existing active jobs
UPDATE external_job_postings SET seniority_label = CASE
    WHEN LOWER(title) ~ '(intern|internship)' THEN 'Intern'
    WHEN LOWER(title) ~ '(entry.level|new.grad|junior| jr | jr$)' THEN 'Entry'
    WHEN LOWER(title) ~ '(director|vp |vice president|chief|head of)' THEN 'Director+'
    WHEN LOWER(title) ~ '(principal|distinguished)' THEN 'Staff+'
    WHEN LOWER(title) ~ '(\ystaff\y)' THEN 'Staff+'
    WHEN LOWER(title) ~ '(lead|tech lead|team lead)' THEN 'Lead'
    WHEN LOWER(title) ~ '(senior| sr )' THEN 'Senior'
    WHEN LOWER(title) ~ '( ii | iii )' THEN 'Mid'
    ELSE NULL
END
WHERE seniority_label IS NULL;

-- Backfill experience_years from description_text using regex extraction
-- Looks for patterns like "3+ years", "5-7 years of experience", "minimum 2 years"
UPDATE external_job_postings SET experience_years = extracted.years
FROM (
    SELECT id,
           (regexp_matches(description_text, '(\d{1,2})\s*(?:\+|\-\s*\d{1,2})?\s*(?:years?|yrs?)\s*(?:of\s+)?(?:experience|exp|professional|relevant|work|industry|related|minimum|min)', 'i'))[1]::SMALLINT AS years
    FROM external_job_postings
    WHERE description_text IS NOT NULL
      AND description_text != ''
      AND experience_years IS NULL
      AND is_active = true
) AS extracted
WHERE external_job_postings.id = extracted.id
  AND extracted.years BETWEEN 0 AND 25;

-- For jobs that got seniority from title but no years from description, infer years
UPDATE external_job_postings SET experience_years = CASE
    WHEN seniority_label = 'Intern' THEN 0
    WHEN seniority_label = 'Entry' THEN 0
    WHEN seniority_label = 'Mid' THEN 2
    WHEN seniority_label = 'Senior' THEN 5
    WHEN seniority_label = 'Lead' THEN 6
    WHEN seniority_label = 'Staff+' THEN 8
    WHEN seniority_label = 'Director+' THEN 10
    ELSE NULL
END
WHERE experience_years IS NULL
  AND seniority_label IS NOT NULL;

-- Index for experience filter queries
CREATE INDEX IF NOT EXISTS idx_ejp_experience_years
    ON external_job_postings (experience_years)
    WHERE is_active = true AND experience_years IS NOT NULL;
