-- V23: Add the first verified source for the new connector set.
-- Workday's public CXS endpoint can be synced without credentials. BambooHR requires
-- an API key, and Jobvite/iCIMS/JazzHR page ingestion depends on verified career-page
-- JSON-LD, so those should be enabled through config/DB rows per known-good company.

INSERT INTO external_companies (name, normalized_name, domain, logo_url, brand_color)
VALUES (
    'Workday',
    'workday',
    'workday.com',
    'https://www.google.com/s2/favicons?domain=workday.com&sz=128',
    '#F4A51C'
)
ON CONFLICT (normalized_name) DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    logo_url = EXCLUDED.logo_url,
    brand_color = EXCLUDED.brand_color,
    is_active = true,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'WORKDAY', 'workday.wd5.myworkdayjobs.com|workday|Workday', 'Workday'
FROM external_companies
WHERE normalized_name = 'workday'
ON CONFLICT (source_type, board_token) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    is_active = true,
    last_error = NULL,
    updated_at = CURRENT_TIMESTAMP;
