-- V5: Rich candidate profiles
-- Separate from users table — only APPLICANT role users will have a record here.
-- skills, experience, education stored as JSONB for flexibility without schema churn.

CREATE TABLE candidate_profiles (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Public-facing identity
    headline            VARCHAR(220),
    bio                 TEXT,
    avatar_url          TEXT,
    location            VARCHAR(150),

    -- Structured data (JSONB arrays)
    -- skills:     ["Java","Spring Boot","React"]
    -- experience: [{"company":"Stripe","title":"SWE","startDate":"2021-01","endDate":"2023-06","description":"..."}]
    -- education:  [{"school":"MIT","degree":"BSc","field":"CS","graduationYear":2021}]
    skills              JSONB NOT NULL DEFAULT '[]'::jsonb,
    experience          JSONB NOT NULL DEFAULT '[]'::jsonb,
    education           JSONB NOT NULL DEFAULT '[]'::jsonb,

    -- Media
    resume_url          TEXT,
    video_intro_url     TEXT,

    -- Computed score 0-100 (updated on every profile save)
    profile_completion  INT NOT NULL DEFAULT 0,

    -- Open-to-work signal
    open_to_work        BOOLEAN NOT NULL DEFAULT false,
    preferred_employment_type VARCHAR(20),  -- FULL_TIME, PART_TIME, CONTRACT
    preferred_work_mode       VARCHAR(10),  -- REMOTE, HYBRID, ONSITE
    salary_expectation_min    NUMERIC(12,2),
    salary_expectation_max    NUMERIC(12,2),
    salary_currency           VARCHAR(10) DEFAULT 'USD',

    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_candidate_profile_user UNIQUE (user_id)
);

CREATE INDEX idx_candidate_profiles_user_id ON candidate_profiles(user_id);
CREATE INDEX idx_candidate_profiles_open_to_work ON candidate_profiles(open_to_work) WHERE open_to_work = true;
