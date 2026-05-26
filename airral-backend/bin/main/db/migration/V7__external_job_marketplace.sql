-- V7: Cached external job marketplace
-- Separate from HR tenant jobs. These tables power the applicant portal's
-- public company/job feed from ATS sources such as Greenhouse, Ashby, and Lever.

CREATE TABLE external_companies (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    normalized_name     VARCHAR(255) NOT NULL UNIQUE,
    domain              VARCHAR(255),
    logo_url            TEXT,
    cover_image_url     TEXT,
    brand_color         VARCHAR(20),
    verification_status VARCHAR(30) NOT NULL DEFAULT 'VERIFIED',
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE external_job_sources (
    id              BIGSERIAL PRIMARY KEY,
    company_id      BIGINT NOT NULL REFERENCES external_companies(id) ON DELETE CASCADE,
    source_type     VARCHAR(30) NOT NULL,
    board_token     VARCHAR(255) NOT NULL,
    source_name     VARCHAR(100) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    last_synced_at  TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_external_job_source UNIQUE (source_type, board_token)
);

CREATE TABLE external_job_postings (
    id                      BIGSERIAL PRIMARY KEY,
    company_id              BIGINT NOT NULL REFERENCES external_companies(id) ON DELETE CASCADE,
    job_source_id           BIGINT NOT NULL REFERENCES external_job_sources(id) ON DELETE CASCADE,

    source_type             VARCHAR(30) NOT NULL,
    source_name             VARCHAR(100) NOT NULL,
    source_board_token      VARCHAR(255) NOT NULL,
    external_job_id         VARCHAR(500) NOT NULL,
    source_job_key          VARCHAR(900) NOT NULL UNIQUE,

    title                   VARCHAR(500) NOT NULL,
    department              VARCHAR(255),
    location                VARCHAR(500),
    work_mode               VARCHAR(30),
    employment_type         VARCHAR(80),

    salary_label            VARCHAR(255),
    salary_min              NUMERIC(12,2),
    salary_max              NUMERIC(12,2),
    salary_currency         VARCHAR(10),

    apply_url               TEXT,
    job_url                 TEXT,
    apply_mode              VARCHAR(40) NOT NULL DEFAULT 'EXTERNAL_APPLY',
    easy_apply_available    BOOLEAN NOT NULL DEFAULT false,

    description_html        TEXT,
    description_text        TEXT,
    description_excerpt     TEXT,

    source_updated_at       TIMESTAMPTZ,
    posted_label            VARCHAR(80),
    match_score             INT,
    connections_count       INT NOT NULL DEFAULT 0,
    tags                    TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    source_payload_hash     VARCHAR(128),

    is_active               BOOLEAN NOT NULL DEFAULT true,
    first_seen_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at              TIMESTAMPTZ NOT NULL,
    deleted_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_external_job_posting UNIQUE (source_type, source_board_token, external_job_id)
);

CREATE TABLE external_job_sync_runs (
    id              BIGSERIAL PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    sources_count   INT NOT NULL DEFAULT 0,
    jobs_seen       INT NOT NULL DEFAULT 0,
    jobs_upserted   INT NOT NULL DEFAULT 0,
    jobs_expired    INT NOT NULL DEFAULT 0,
    error_message   TEXT
);

CREATE INDEX idx_external_companies_active ON external_companies(is_active) WHERE is_active = true;
CREATE INDEX idx_external_job_sources_active ON external_job_sources(is_active) WHERE is_active = true;
CREATE INDEX idx_external_job_postings_active_recent ON external_job_postings(is_active, source_updated_at DESC);
CREATE INDEX idx_external_job_postings_company ON external_job_postings(company_id);
CREATE INDEX idx_external_job_postings_source ON external_job_postings(job_source_id);
CREATE INDEX idx_external_job_postings_expires ON external_job_postings(expires_at);
CREATE INDEX idx_external_job_postings_title_lower ON external_job_postings(LOWER(title));

INSERT INTO external_companies (name, normalized_name, domain, logo_url, brand_color)
VALUES
    ('Airbnb', 'airbnb', 'airbnb.com', 'https://www.google.com/s2/favicons?domain=airbnb.com&sz=128', '#FF5A5F'),
    ('Figma', 'figma', 'figma.com', 'https://www.google.com/s2/favicons?domain=figma.com&sz=128', '#0ACF83'),
    ('DoorDash', 'doordash', 'doordash.com', 'https://www.google.com/s2/favicons?domain=doordash.com&sz=128', '#FF3008'),
    ('Ashby', 'ashby', 'ashbyhq.com', 'https://www.google.com/s2/favicons?domain=ashbyhq.com&sz=128', '#1D4ED8'),
    ('Ramp', 'ramp', 'ramp.com', 'https://www.google.com/s2/favicons?domain=ramp.com&sz=128', '#00A76F')
ON CONFLICT (normalized_name) DO NOTHING;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'GREENHOUSE', 'airbnb', 'Greenhouse' FROM external_companies WHERE normalized_name = 'airbnb'
ON CONFLICT (source_type, board_token) DO NOTHING;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'GREENHOUSE', 'figma', 'Greenhouse' FROM external_companies WHERE normalized_name = 'figma'
ON CONFLICT (source_type, board_token) DO NOTHING;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'GREENHOUSE', 'doordashusa', 'Greenhouse' FROM external_companies WHERE normalized_name = 'doordash'
ON CONFLICT (source_type, board_token) DO NOTHING;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'ASHBY', 'Ashby', 'Ashby' FROM external_companies WHERE normalized_name = 'ashby'
ON CONFLICT (source_type, board_token) DO NOTHING;

INSERT INTO external_job_sources (company_id, source_type, board_token, source_name)
SELECT id, 'ASHBY', 'Ramp', 'Ashby' FROM external_companies WHERE normalized_name = 'ramp'
ON CONFLICT (source_type, board_token) DO NOTHING;
