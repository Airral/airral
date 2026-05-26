-- V2: Add HR encounters and activity feed tables (Phase 3)

CREATE TABLE IF NOT EXISTS hr_encounters (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    encounter_type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    notes TEXT,
    application_id BIGINT REFERENCES applications(id) ON DELETE SET NULL,
    job_id BIGINT REFERENCES jobs(id) ON DELETE SET NULL,
    candidate_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    performed_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    interview_id BIGINT REFERENCES interviews(id) ON DELETE SET NULL,
    offer_id BIGINT REFERENCES offers(id) ON DELETE SET NULL,
    outcome VARCHAR(50),
    rating INT CHECK (rating >= 1 AND rating <= 5),
    recommendation VARCHAR(50),
    priority VARCHAR(20),
    encountered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metadata JSONB
);

CREATE TABLE IF NOT EXISTS activity_feed (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    activity_type VARCHAR(100) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description TEXT,
    action_url VARCHAR(1000),
    actor_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    actor_name VARCHAR(255),
    actor_role VARCHAR(50),
    target_type VARCHAR(50),
    target_id BIGINT,
    target_name VARCHAR(255),
    is_public BOOLEAN NOT NULL DEFAULT true,
    visible_to_roles VARCHAR(255),
    priority VARCHAR(20),
    category VARCHAR(50),
    icon VARCHAR(50),
    metadata JSONB,
    activity_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_hr_encounters_org ON hr_encounters(organization_id);
CREATE INDEX IF NOT EXISTS idx_hr_encounters_application ON hr_encounters(application_id);
CREATE INDEX IF NOT EXISTS idx_hr_encounters_job ON hr_encounters(job_id);
CREATE INDEX IF NOT EXISTS idx_hr_encounters_type ON hr_encounters(encounter_type);
CREATE INDEX IF NOT EXISTS idx_hr_encounters_when ON hr_encounters(encountered_at DESC);

CREATE INDEX IF NOT EXISTS idx_activity_feed_org ON activity_feed(organization_id);
CREATE INDEX IF NOT EXISTS idx_activity_feed_type ON activity_feed(activity_type);
CREATE INDEX IF NOT EXISTS idx_activity_feed_category ON activity_feed(category);
CREATE INDEX IF NOT EXISTS idx_activity_feed_actor ON activity_feed(actor_id);
CREATE INDEX IF NOT EXISTS idx_activity_feed_when ON activity_feed(activity_at DESC);
