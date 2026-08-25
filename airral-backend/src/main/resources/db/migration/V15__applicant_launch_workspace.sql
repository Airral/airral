-- V15: Applicant launch workspace keeper migration.
--
-- Audit decision:
-- Keep now:
--   1. Resume document metadata and parsed resume output.
--   2. Saved jobs and application tracker state.
--   3. Selected-job resume fit results.
--   4. Visa/work-authorization signals on discovered jobs.
--   5. Company-level immigration signals for DOL/USCIS/E-Verify enrichment.
--
-- Defer:
--   1. Rooms, direct messages, founder spaces, event rooms, and feed follow-up threads.
--      Those are product features for a later unlock, not launch-critical schema.
--
-- Do not keep:
--   1. Separate V15/V16/V17 experimental split migrations for this launch batch.
--      This file is the single keeper migration after V14.

CREATE TABLE IF NOT EXISTS candidate_resume_documents (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    candidate_profile_id     BIGINT REFERENCES candidate_profiles(id) ON DELETE SET NULL,

    storage_provider         VARCHAR(40) NOT NULL DEFAULT 'LOCAL',
    storage_bucket           VARCHAR(255),
    storage_key              TEXT NOT NULL,

    original_file_name       VARCHAR(255),
    content_type             VARCHAR(160),
    file_extension           VARCHAR(20),
    file_size_bytes          BIGINT NOT NULL,
    sha256                   VARCHAR(64) NOT NULL,

    parse_status             VARCHAR(40) NOT NULL DEFAULT 'UPLOADED',
    parse_error              TEXT,
    extracted_text           TEXT,
    parsed_skills            JSONB NOT NULL DEFAULT '[]'::jsonb,
    parsed_experience        JSONB NOT NULL DEFAULT '[]'::jsonb,
    parsed_education         JSONB NOT NULL DEFAULT '[]'::jsonb,
    parsed_profile           JSONB NOT NULL DEFAULT '{}'::jsonb,
    parsed_at                TIMESTAMPTZ,

    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE candidate_profiles
    ADD COLUMN IF NOT EXISTS active_resume_document_id BIGINT REFERENCES candidate_resume_documents(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS resume_parse_status VARCHAR(40),
    ADD COLUMN IF NOT EXISTS resume_parsed_at TIMESTAMPTZ;

ALTER TABLE external_job_postings
    ADD COLUMN IF NOT EXISTS sponsorship_language VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS visa_confidence_score INT,
    ADD COLUMN IF NOT EXISTS visa_reasons TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    ADD COLUMN IF NOT EXISTS requires_us_work_authorization BOOLEAN,
    ADD COLUMN IF NOT EXISTS contract_or_staffing_risk BOOLEAN,
    ADD COLUMN IF NOT EXISTS stem_opt_risk BOOLEAN,
    ADD COLUMN IF NOT EXISTS h1b_transfer_fit BOOLEAN,
    ADD COLUMN IF NOT EXISTS cap_exempt_fit BOOLEAN;

CREATE TABLE IF NOT EXISTS company_immigration_signals (
    id                              BIGSERIAL PRIMARY KEY,
    company_id                      BIGINT REFERENCES external_companies(id) ON DELETE CASCADE,
    normalized_employer_name         VARCHAR(255) NOT NULL,
    h1b_lca_count_recent             INT NOT NULL DEFAULT 0,
    h1b_lca_count_total              INT NOT NULL DEFAULT 0,
    h1b_uscis_approval_count_recent  INT NOT NULL DEFAULT 0,
    h1b_uscis_denial_count_recent    INT NOT NULL DEFAULT 0,
    perm_count_recent                INT NOT NULL DEFAULT 0,
    top_soc_codes                    TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    top_worksite_states              TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    median_lca_wage                  NUMERIC(12,2),
    latest_lca_filed_at              TIMESTAMPTZ,
    latest_perm_filed_at             TIMESTAMPTZ,
    sponsor_confidence_score         INT,
    cap_exempt_likelihood            VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    everify_status                   VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    source_summary                   TEXT,
    last_refreshed_at                TIMESTAMPTZ,
    created_at                       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_company_immigration_signal UNIQUE (normalized_employer_name)
);

CREATE TABLE IF NOT EXISTS candidate_job_fit_results (
    id                       BIGSERIAL PRIMARY KEY,
    user_id                  BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_job_key           VARCHAR(900) NOT NULL,
    resume_document_id       BIGINT REFERENCES candidate_resume_documents(id) ON DELETE SET NULL,
    fit_score                INT NOT NULL,
    visa_fit_score           INT,
    matched_requirements     JSONB NOT NULL DEFAULT '[]'::jsonb,
    missing_requirements     JSONB NOT NULL DEFAULT '[]'::jsonb,
    keyword_gaps             JSONB NOT NULL DEFAULT '[]'::jsonb,
    weak_bullets             JSONB NOT NULL DEFAULT '[]'::jsonb,
    suggested_rewrites       JSONB NOT NULL DEFAULT '[]'::jsonb,
    application_checklist    JSONB NOT NULL DEFAULT '[]'::jsonb,
    generated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS candidate_saved_jobs (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_job_key       VARCHAR(900) NOT NULL,
    status               VARCHAR(40) NOT NULL DEFAULT 'SAVED',
    resume_document_id   BIGINT REFERENCES candidate_resume_documents(id) ON DELETE SET NULL,
    fit_result_id        BIGINT REFERENCES candidate_job_fit_results(id) ON DELETE SET NULL,
    next_step            VARCHAR(255),
    next_step_due_at     TIMESTAMPTZ,
    notes                TEXT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_candidate_saved_job UNIQUE (user_id, source_job_key),
    CONSTRAINT candidate_saved_jobs_status_check CHECK (
        status IN ('SAVED', 'APPLYING', 'APPLIED', 'INTERVIEWING', 'OFFER', 'REJECTED', 'ARCHIVED')
    )
);

CREATE INDEX IF NOT EXISTS idx_candidate_resume_documents_user
    ON candidate_resume_documents(user_id);
CREATE INDEX IF NOT EXISTS idx_candidate_resume_documents_profile
    ON candidate_resume_documents(candidate_profile_id);
CREATE INDEX IF NOT EXISTS idx_candidate_resume_documents_user_created
    ON candidate_resume_documents(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_candidate_resume_documents_user_sha
    ON candidate_resume_documents(user_id, sha256);
CREATE INDEX IF NOT EXISTS idx_candidate_resume_documents_parse_status
    ON candidate_resume_documents(parse_status);

CREATE INDEX IF NOT EXISTS idx_external_job_postings_visa
    ON external_job_postings(sponsorship_language, visa_confidence_score DESC);
CREATE INDEX IF NOT EXISTS idx_company_immigration_signals_company
    ON company_immigration_signals(company_id);
CREATE INDEX IF NOT EXISTS idx_company_immigration_signals_employer
    ON company_immigration_signals(LOWER(normalized_employer_name));
CREATE INDEX IF NOT EXISTS idx_candidate_fit_user_job
    ON candidate_job_fit_results(user_id, source_job_key, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_candidate_saved_jobs_user_updated
    ON candidate_saved_jobs(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_candidate_saved_jobs_status
    ON candidate_saved_jobs(user_id, status);
