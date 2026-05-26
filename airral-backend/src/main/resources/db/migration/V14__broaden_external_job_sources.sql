-- V14: Broaden applicant job discovery beyond the original startup-heavy seed set.
-- Adds verified public ATS boards across finance, healthcare, education, hospitality,
-- manufacturing, construction, consumer, defense, and enterprise services.

WITH companies(name, normalized_name, domain, logo_url, brand_color) AS (
    VALUES
        ('Stripe', 'stripe', 'stripe.com', 'https://www.google.com/s2/favicons?domain=stripe.com&sz=128', '#635BFF'),
        ('Chime', 'chime', 'chime.com', 'https://www.google.com/s2/favicons?domain=chime.com&sz=128', '#00A86B'),
        ('Affirm', 'affirm', 'affirm.com', 'https://www.google.com/s2/favicons?domain=affirm.com&sz=128', '#4A4AF4'),
        ('Robinhood', 'robinhood', 'robinhood.com', 'https://www.google.com/s2/favicons?domain=robinhood.com&sz=128', '#00C805'),
        ('Datadog', 'datadog', 'datadoghq.com', 'https://www.google.com/s2/favicons?domain=datadoghq.com&sz=128', '#632CA6'),
        ('Cloudflare', 'cloudflare', 'cloudflare.com', 'https://www.google.com/s2/favicons?domain=cloudflare.com&sz=128', '#F38020'),
        ('Peloton', 'peloton', 'onepeloton.com', 'https://www.google.com/s2/favicons?domain=onepeloton.com&sz=128', '#181A1D'),
        ('Toast', 'toast', 'toasttab.com', 'https://www.google.com/s2/favicons?domain=toasttab.com&sz=128', '#F15B2A'),
        ('Instacart', 'instacart', 'instacart.com', 'https://www.google.com/s2/favicons?domain=instacart.com&sz=128', '#43B02A'),
        ('Anduril', 'anduril', 'anduril.com', 'https://www.google.com/s2/favicons?domain=anduril.com&sz=128', '#0B0F19'),
        ('Samsara', 'samsara', 'samsara.com', 'https://www.google.com/s2/favicons?domain=samsara.com&sz=128', '#FF4F00'),
        ('Oscar Health', 'oscar-health', 'hioscar.com', 'https://www.google.com/s2/favicons?domain=hioscar.com&sz=128', '#00A4E4'),
        ('Duolingo', 'duolingo', 'duolingo.com', 'https://www.google.com/s2/favicons?domain=duolingo.com&sz=128', '#58CC02'),
        ('Coursera', 'coursera', 'coursera.org', 'https://www.google.com/s2/favicons?domain=coursera.org&sz=128', '#0056D2'),
        ('Visa', 'visa', 'visa.com', 'https://www.google.com/s2/favicons?domain=visa.com&sz=128', '#1A1F71'),
        ('Bosch Group', 'bosch-group', 'bosch.com', 'https://www.google.com/s2/favicons?domain=bosch.com&sz=128', '#E20015'),
        ('Western Digital', 'western-digital', 'westerndigital.com', 'https://www.google.com/s2/favicons?domain=westerndigital.com&sz=128', '#005195'),
        ('Experian', 'experian', 'experian.com', 'https://www.google.com/s2/favicons?domain=experian.com&sz=128', '#7A1FA2'),
        ('ServiceNow', 'servicenow', 'servicenow.com', 'https://www.google.com/s2/favicons?domain=servicenow.com&sz=128', '#81B5A1'),
        ('Turner & Townsend', 'turner-townsend', 'turnerandtownsend.com', 'https://www.google.com/s2/favicons?domain=turnerandtownsend.com&sz=128', '#1F3B73'),
        ('SGS', 'sgs', 'sgs.com', 'https://www.google.com/s2/favicons?domain=sgs.com&sz=128', '#F4C300'),
        ('Accor', 'accor', 'accor.com', 'https://www.google.com/s2/favicons?domain=accor.com&sz=128', '#003B5C'),
        ('NielsenIQ', 'nielseniq', 'nielseniq.com', 'https://www.google.com/s2/favicons?domain=nielseniq.com&sz=128', '#00A3E0')
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
        ('stripe', 'GREENHOUSE', 'stripe', 'Greenhouse'),
        ('chime', 'GREENHOUSE', 'chime', 'Greenhouse'),
        ('affirm', 'GREENHOUSE', 'affirm', 'Greenhouse'),
        ('robinhood', 'GREENHOUSE', 'robinhood', 'Greenhouse'),
        ('datadog', 'GREENHOUSE', 'datadog', 'Greenhouse'),
        ('cloudflare', 'GREENHOUSE', 'cloudflare', 'Greenhouse'),
        ('peloton', 'GREENHOUSE', 'peloton', 'Greenhouse'),
        ('toast', 'GREENHOUSE', 'toast', 'Greenhouse'),
        ('instacart', 'GREENHOUSE', 'instacart', 'Greenhouse'),
        ('anduril', 'GREENHOUSE', 'andurilindustries', 'Greenhouse'),
        ('samsara', 'GREENHOUSE', 'samsara', 'Greenhouse'),
        ('oscar-health', 'GREENHOUSE', 'oscar', 'Greenhouse'),
        ('duolingo', 'GREENHOUSE', 'duolingo', 'Greenhouse'),
        ('coursera', 'GREENHOUSE', 'coursera', 'Greenhouse'),
        ('visa', 'SMARTRECRUITERS', 'Visa', 'SmartRecruiters'),
        ('bosch-group', 'SMARTRECRUITERS', 'BoschGroup', 'SmartRecruiters'),
        ('western-digital', 'SMARTRECRUITERS', 'WesternDigital', 'SmartRecruiters'),
        ('experian', 'SMARTRECRUITERS', 'Experian', 'SmartRecruiters'),
        ('servicenow', 'SMARTRECRUITERS', 'ServiceNow', 'SmartRecruiters'),
        ('turner-townsend', 'SMARTRECRUITERS', 'TurnerTownsend', 'SmartRecruiters'),
        ('sgs', 'SMARTRECRUITERS', 'SGS', 'SmartRecruiters'),
        ('accor', 'SMARTRECRUITERS', 'AccorHotel', 'SmartRecruiters'),
        ('nielseniq', 'SMARTRECRUITERS', 'NielsenIQ', 'SmartRecruiters')
)
INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT c.id, s.source_type, s.board_token, s.source_name
FROM sources s
JOIN external_companies c ON c.normalized_name = s.normalized_name
ON CONFLICT (source_type, board_token) DO UPDATE SET
    source_name = EXCLUDED.source_name,
    is_active = true,
    updated_at = CURRENT_TIMESTAMP;
