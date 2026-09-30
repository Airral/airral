-- A company's interview kits: what to ask in an interview, and what to rate.
-- questions: [{"text": "...", "category": "..."}]
-- criteria:  [{"name": "...", "category": "...", "weight": 1-3}]
CREATE TABLE interview_kits (
    id               BIGSERIAL PRIMARY KEY,
    organization_id  BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name             VARCHAR(120) NOT NULL,
    description      TEXT,
    duration_minutes INT NOT NULL DEFAULT 60
        CONSTRAINT interview_kits_duration_check CHECK (duration_minutes BETWEEN 15 AND 480),
    questions        JSONB NOT NULL DEFAULT '[]'::jsonb,
    criteria         JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_interview_kits_org_name ON interview_kits (organization_id, lower(name));

-- The kit a job's interviews use. Removing a kit leaves its jobs without one.
ALTER TABLE jobs ADD COLUMN interview_kit_id BIGINT REFERENCES interview_kits(id) ON DELETE SET NULL;

-- Each interviewer's scorecard for one interview. A submitted scorecard keeps
-- the criteria it was rated on, even if the kit changes later.
-- ratings: [{"criterion": "...", "category": "...", "weight": 1-3, "rating": 1-5 or null, "notes": "..."}]
CREATE TABLE interview_scorecards (
    id             BIGSERIAL PRIMARY KEY,
    interview_id   BIGINT NOT NULL REFERENCES interviews(id) ON DELETE CASCADE,
    interviewer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ratings        JSONB NOT NULL DEFAULT '[]'::jsonb,
    overall_notes  TEXT,
    recommendation VARCHAR(20)
        CONSTRAINT interview_scorecards_recommendation_check
        CHECK (recommendation IN ('STRONG_HIRE', 'HIRE', 'NO_HIRE', 'STRONG_NO_HIRE')),
    status         VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT interview_scorecards_status_check CHECK (status IN ('DRAFT', 'SUBMITTED')),
    submitted_at   TIMESTAMP,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_interview_scorecards_interviewer UNIQUE (interview_id, interviewer_id)
);

CREATE INDEX idx_interview_scorecards_interviewer ON interview_scorecards (interviewer_id);
