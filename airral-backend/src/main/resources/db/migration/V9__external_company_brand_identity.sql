-- V9: Production company identity and brand assets.
-- Keep external_companies as the canonical company row, then hang source-specific
-- identifiers, aliases, and image assets off it with provenance.

ALTER TABLE external_companies
    ADD COLUMN legal_name VARCHAR(255),
    ADD COLUMN website_url TEXT,
    ADD COLUMN linkedin_org_id VARCHAR(100),
    ADD COLUMN linkedin_vanity_name VARCHAR(255),
    ADD COLUMN description TEXT,
    ADD COLUMN industry VARCHAR(255),
    ADD COLUMN headquarters_city VARCHAR(120),
    ADD COLUMN headquarters_region VARCHAR(120),
    ADD COLUMN headquarters_country VARCHAR(2),
    ADD COLUMN employee_count_range VARCHAR(80),
    ADD COLUMN confidence_score NUMERIC(5,2) NOT NULL DEFAULT 0.70,
    ADD COLUMN brand_source VARCHAR(80),
    ADD COLUMN brand_enriched_at TIMESTAMPTZ,
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::JSONB;

UPDATE external_companies
SET website_url = 'https://' || domain
WHERE website_url IS NULL
  AND domain IS NOT NULL
  AND domain <> '';

CREATE TABLE external_company_aliases (
    id                BIGSERIAL PRIMARY KEY,
    company_id        BIGINT NOT NULL REFERENCES external_companies(id) ON DELETE CASCADE,
    alias             VARCHAR(255) NOT NULL,
    normalized_alias  VARCHAR(255) NOT NULL,
    source_name       VARCHAR(80) NOT NULL,
    source_value      VARCHAR(500),
    confidence_score  NUMERIC(5,2) NOT NULL DEFAULT 0.70,
    is_primary        BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_external_company_alias UNIQUE (company_id, normalized_alias, source_name)
);

CREATE TABLE external_company_identifiers (
    id                BIGSERIAL PRIMARY KEY,
    company_id        BIGINT NOT NULL REFERENCES external_companies(id) ON DELETE CASCADE,
    identifier_type   VARCHAR(60) NOT NULL,
    identifier_value  VARCHAR(500) NOT NULL,
    source_name       VARCHAR(80) NOT NULL,
    confidence_score  NUMERIC(5,2) NOT NULL DEFAULT 0.80,
    verified_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_external_company_identifier UNIQUE (identifier_type, identifier_value)
);

CREATE TABLE external_company_assets (
    id                    BIGSERIAL PRIMARY KEY,
    company_id            BIGINT NOT NULL REFERENCES external_companies(id) ON DELETE CASCADE,
    asset_type            VARCHAR(40) NOT NULL,
    variant               VARCHAR(40) NOT NULL DEFAULT 'DEFAULT',
    source_name           VARCHAR(80) NOT NULL,
    source_url            TEXT,
    cached_url            TEXT,
    mime_type             VARCHAR(80),
    width                 INT,
    height                INT,
    dominant_color        VARCHAR(20),
    blurhash              TEXT,
    content_hash          VARCHAR(128),
    attribution_required  BOOLEAN NOT NULL DEFAULT false,
    attribution_text      TEXT,
    license_note          TEXT,
    confidence_score      NUMERIC(5,2) NOT NULL DEFAULT 0.70,
    is_primary            BOOLEAN NOT NULL DEFAULT false,
    last_checked_at       TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_external_company_asset_source UNIQUE (company_id, asset_type, variant, source_name)
);

CREATE TABLE external_company_enrichment_runs (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT REFERENCES external_companies(id) ON DELETE SET NULL,
    source_name     VARCHAR(80) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    fields_updated  TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    error_message   TEXT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX idx_external_companies_domain ON external_companies(LOWER(domain));
CREATE INDEX idx_external_companies_linkedin_org ON external_companies(linkedin_org_id) WHERE linkedin_org_id IS NOT NULL;
CREATE INDEX idx_external_companies_linkedin_vanity ON external_companies(LOWER(linkedin_vanity_name)) WHERE linkedin_vanity_name IS NOT NULL;
CREATE INDEX idx_external_company_aliases_normalized ON external_company_aliases(normalized_alias);
CREATE INDEX idx_external_company_identifiers_company ON external_company_identifiers(company_id);
CREATE INDEX idx_external_company_assets_primary ON external_company_assets(company_id, asset_type, is_primary);
CREATE INDEX idx_external_company_assets_expiry ON external_company_assets(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX idx_external_company_enrichment_runs_company ON external_company_enrichment_runs(company_id, started_at DESC);

INSERT INTO external_company_aliases (company_id, alias, normalized_alias, source_name, source_value, confidence_score, is_primary)
SELECT id, name, normalized_name, 'AIRRAL_SEED', normalized_name, 0.95, true
FROM external_companies
ON CONFLICT (company_id, normalized_alias, source_name) DO NOTHING;

INSERT INTO external_company_identifiers (company_id, identifier_type, identifier_value, source_name, confidence_score, verified_at)
SELECT id, 'DOMAIN', domain, 'AIRRAL_SEED', 0.90, CURRENT_TIMESTAMP
FROM external_companies
WHERE domain IS NOT NULL
  AND domain <> ''
ON CONFLICT (identifier_type, identifier_value) DO NOTHING;

INSERT INTO external_company_assets (
    company_id,
    asset_type,
    variant,
    source_name,
    source_url,
    cached_url,
    attribution_required,
    attribution_text,
    license_note,
    confidence_score,
    is_primary,
    last_checked_at
)
SELECT
    id,
    'ICON',
    'DEFAULT',
    'GOOGLE_FAVICON',
    logo_url,
    logo_url,
    false,
    NULL,
    'Fallback favicon source. Replace with employer-uploaded or licensed brand asset when available.',
    0.55,
    true,
    CURRENT_TIMESTAMP
FROM external_companies
WHERE logo_url IS NOT NULL
  AND logo_url <> ''
ON CONFLICT (company_id, asset_type, variant, source_name) DO NOTHING;
