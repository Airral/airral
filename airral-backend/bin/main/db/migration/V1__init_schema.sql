-- V1: Initial schema

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(100) UNIQUE,
    tier VARCHAR(50) NOT NULL DEFAULT 'QUICK_HIRE',
    subscription_status VARCHAR(50) DEFAULT 'ACTIVE',
    subscription_start_date DATE,
    subscription_end_date DATE,
    monthly_price NUMERIC(10,2),
    max_users INT DEFAULT 10,
    max_jobs INT DEFAULT 2,
    max_applications_per_month INT DEFAULT 100,
    primary_contact_email VARCHAR(255),
    primary_contact_phone VARCHAR(50),
    billing_email VARCHAR(255),
    settings JSONB DEFAULT '{}'::jsonb,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE departments (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_department_per_org UNIQUE (organization_id, name)
);

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone VARCHAR(50),
    role VARCHAR(50) NOT NULL DEFAULT 'APPLICANT',
    is_platform_admin BOOLEAN DEFAULT false,
    manager_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL,
    department VARCHAR(100),
    job_title VARCHAR(100),
    location VARCHAR(255),
    resume_url VARCHAR(500),
    headline VARCHAR(255),
    skills TEXT[],
    years_of_experience INT,
    is_active BOOLEAN DEFAULT true,
    email_verified BOOLEAN DEFAULT false,
    invitation_token VARCHAR(255),
    invitation_expires_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP,
    created_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE user_credentials (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_verified BOOLEAN DEFAULT false,
    email_verification_token VARCHAR(255),
    email_verification_expires_at TIMESTAMP,
    password_reset_token VARCHAR(255),
    password_reset_expires_at TIMESTAMP,
    last_login_at TIMESTAMP,
    failed_login_attempts INT DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    is_primary BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_user_role UNIQUE (user_id, role)
);

CREATE TABLE user_invitations (
    id BIGSERIAL PRIMARY KEY,
    invited_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    department VARCHAR(100),
    invitation_token VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    is_accepted BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE jobs (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,
    department_id BIGINT REFERENCES departments(id) ON DELETE SET NULL,
    description TEXT,
    department VARCHAR(100),
    location VARCHAR(255),
    employment_type VARCHAR(50) DEFAULT 'Full-time',
    salary_min NUMERIC(12,2),
    salary_max NUMERIC(12,2),
    currency VARCHAR(10) DEFAULT 'USD',
    requirements TEXT,
    nice_to_have TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    ats_keywords TEXT[],
    ats_weights JSONB,
    ats_min_score INT DEFAULT 70,
    linkedin_post_id VARCHAR(100),
    linkedin_enabled BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE applications (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    applicant_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    applicant_name VARCHAR(255),
    applicant_email VARCHAR(255) NOT NULL,
    applicant_phone VARCHAR(50),
    resume_url VARCHAR(500),
    resume_text TEXT,
    cover_letter TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'SUBMITTED',
    ats_score INT,
    ats_matched_keywords TEXT[],
    ats_missing_keywords TEXT[],
    ats_match_details JSONB,
    visible_to_hr BOOLEAN DEFAULT true,
    reviewed_by_hr_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reviewed_by_hr_at TIMESTAMP,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_notes (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    author_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE application_activities (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    action VARCHAR(100) NOT NULL,
    performed_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE referrals (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    referred_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    referred_name VARCHAR(255) NOT NULL,
    referred_email VARCHAR(255) NOT NULL,
    referred_phone VARCHAR(50),
    job_id BIGINT REFERENCES jobs(id) ON DELETE SET NULL,
    notes TEXT,
    relationship VARCHAR(100),
    resume_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    reviewed_at TIMESTAMP,
    reviewed_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    application_id BIGINT REFERENCES applications(id) ON DELETE SET NULL,
    bonus_amount NUMERIC(12,2),
    bonus_paid_at TIMESTAMP,
    submitted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE interviews (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    scheduled_by_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    interview_date TIMESTAMP NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'SCHEDULED',
    feedback TEXT,
    rating INT,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT interviews_rating_check CHECK (rating >= 1 AND rating <= 5)
);

CREATE TABLE offers (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    job_id BIGINT REFERENCES jobs(id) ON DELETE SET NULL,
    salary NUMERIC(12,2) NOT NULL,
    currency VARCHAR(10) DEFAULT 'USD',
    start_date DATE,
    offer_letter TEXT,
    benefits TEXT,
    contingencies TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    sent_at TIMESTAMP,
    expires_at TIMESTAMP,
    responded_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id BIGINT,
    details JSONB,
    ip_address INET,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_departments_org ON departments(organization_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_organization ON users(organization_id);
CREATE INDEX idx_users_manager ON users(manager_id);
CREATE INDEX idx_users_department_id ON users(department_id);
CREATE INDEX idx_user_credentials_email ON user_credentials(email);
CREATE INDEX idx_user_credentials_user ON user_credentials(user_id);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);
CREATE INDEX idx_user_roles_role ON user_roles(role);
CREATE INDEX idx_user_invitations_email ON user_invitations(email);
CREATE INDEX idx_user_invitations_org ON user_invitations(organization_id);
CREATE UNIQUE INDEX idx_user_invitations_pending_email_org ON user_invitations(email, organization_id) WHERE accepted_at IS NULL;
CREATE INDEX idx_jobs_organization ON jobs(organization_id);
CREATE INDEX idx_jobs_department_id ON jobs(department_id);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_applications_job ON applications(job_id);
CREATE INDEX idx_applications_applicant ON applications(applicant_id);
CREATE INDEX idx_applications_status ON applications(status);
CREATE INDEX idx_application_notes_application ON application_notes(application_id);
CREATE INDEX idx_application_activities_application ON application_activities(application_id);
CREATE INDEX idx_referrals_org ON referrals(organization_id);
CREATE INDEX idx_referrals_status ON referrals(status);
CREATE INDEX idx_interviews_application ON interviews(application_id);
CREATE INDEX idx_offers_application ON offers(application_id);
CREATE INDEX idx_audit_logs_user ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);
