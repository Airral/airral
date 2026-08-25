-- V24: Seed verified sources for the additional connector set.
-- These boards were checked against their public ATS endpoints before enabling.
-- BambooHR remains unseeded until a BambooHR API key is configured.

WITH companies(name, normalized_name, domain, logo_url, brand_color) AS (
    VALUES
        ('Salesforce', 'salesforce', 'salesforce.com', 'https://www.google.com/s2/favicons?domain=salesforce.com&sz=128', '#00A1E0'),
        ('Adobe', 'adobe', 'adobe.com', 'https://www.google.com/s2/favicons?domain=adobe.com&sz=128', '#FA0F00'),
        ('NVIDIA', 'nvidia', 'nvidia.com', 'https://www.google.com/s2/favicons?domain=nvidia.com&sz=128', '#76B900'),
        ('Target', 'target', 'target.com', 'https://www.google.com/s2/favicons?domain=target.com&sz=128', '#CC0000'),
        ('Lowe''s', 'lowes', 'lowes.com', 'https://www.google.com/s2/favicons?domain=lowes.com&sz=128', '#004990'),
        ('Netguru', 'netguru', 'netguru.com', 'https://www.google.com/s2/favicons?domain=netguru.com&sz=128', '#00D563'),
        ('Leadtech', 'leadtech', 'leadtech.com', 'https://www.google.com/s2/favicons?domain=leadtech.com&sz=128', '#111827')
)
INSERT INTO external_companies (name, normalized_name, domain, logo_url, brand_color)
SELECT name, normalized_name, domain, logo_url, brand_color
FROM companies
ON CONFLICT (normalized_name) DO UPDATE SET
    name = EXCLUDED.name,
    domain = EXCLUDED.domain,
    logo_url = EXCLUDED.logo_url,
    brand_color = EXCLUDED.brand_color,
    is_active = true,
    updated_at = CURRENT_TIMESTAMP;

WITH sources(normalized_name, source_type, board_token, source_name) AS (
    VALUES
        ('salesforce', 'WORKDAY', 'salesforce.wd12.myworkdayjobs.com|salesforce|External_Career_Site', 'Workday'),
        ('adobe', 'WORKDAY', 'adobe.wd5.myworkdayjobs.com|adobe|external_experienced', 'Workday'),
        ('nvidia', 'WORKDAY', 'nvidia.wd5.myworkdayjobs.com|nvidia|NVIDIAExternalCareerSite', 'Workday'),
        ('target', 'WORKDAY', 'target.wd5.myworkdayjobs.com|target|targetcareers', 'Workday'),
        ('lowes', 'WORKDAY', 'lowes.wd5.myworkdayjobs.com|lowes|LWS_External_CS', 'Workday'),
        ('netguru', 'WORKABLE', 'netguru', 'Workable'),
        ('leadtech', 'WORKABLE', 'leadtech', 'Workable')
)
INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT c.id, s.source_type, s.board_token, s.source_name
FROM sources s
JOIN external_companies c ON c.normalized_name = s.normalized_name
ON CONFLICT (source_type, board_token) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    is_active = true,
    last_error = NULL,
    updated_at = CURRENT_TIMESTAMP;
