-- V10: Decision-quality data for applicant job browsing.
-- Employer-posted pay stays on external_job_postings. Market/Levels.fyi-style
-- compensation benchmarks live separately so base, bonus, and equity are never
-- blended without source/context.

ALTER TABLE external_job_postings
    ADD COLUMN IF NOT EXISTS external_internal_job_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS job_quality_score INT,
    ADD COLUMN IF NOT EXISTS quality_reasons TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN IF NOT EXISTS total_comp_label VARCHAR(255),
    ADD COLUMN IF NOT EXISTS compensation_confidence VARCHAR(30);

CREATE TABLE IF NOT EXISTS external_compensation_benchmarks (
    id                  BIGSERIAL PRIMARY KEY,
    company_id          BIGINT REFERENCES external_companies(id) ON DELETE CASCADE,
    source_name         VARCHAR(100) NOT NULL,
    source_url          TEXT,
    role_family         VARCHAR(255),
    role_title_pattern  VARCHAR(255),
    job_level           VARCHAR(100),
    location            VARCHAR(255),
    work_mode           VARCHAR(30),
    currency            VARCHAR(10) NOT NULL DEFAULT 'USD',
    base_salary_min     NUMERIC(12,2),
    base_salary_median  NUMERIC(12,2),
    base_salary_max     NUMERIC(12,2),
    bonus_min           NUMERIC(12,2),
    bonus_median        NUMERIC(12,2),
    bonus_max           NUMERIC(12,2),
    equity_min          NUMERIC(12,2),
    equity_median       NUMERIC(12,2),
    equity_max          NUMERIC(12,2),
    total_comp_min      NUMERIC(12,2),
    total_comp_median   NUMERIC(12,2),
    total_comp_max      NUMERIC(12,2),
    sample_size         INT,
    confidence_score    INT,
    collected_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_external_job_postings_quality ON external_job_postings(job_quality_score DESC);
CREATE INDEX IF NOT EXISTS idx_external_comp_bench_company_role ON external_compensation_benchmarks(company_id, role_family, job_level);
CREATE INDEX IF NOT EXISTS idx_external_comp_bench_expires ON external_compensation_benchmarks(expires_at);
