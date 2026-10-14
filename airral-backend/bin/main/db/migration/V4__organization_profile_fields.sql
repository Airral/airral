-- V4: Add organization onboarding/profile columns

ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(100),
    ADD COLUMN IF NOT EXISTS country VARCHAR(100),
    ADD COLUMN IF NOT EXISTS company_size_range VARCHAR(50),
    ADD COLUMN IF NOT EXISTS industry VARCHAR(100),
    ADD COLUMN IF NOT EXISTS logo_url VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS brand_primary_color VARCHAR(20),
    ADD COLUMN IF NOT EXISTS brand_secondary_color VARCHAR(20),
    ADD COLUMN IF NOT EXISTS subscription_plan_preference VARCHAR(50),
    ADD COLUMN IF NOT EXISTS hiring_regions TEXT[],
    ADD COLUMN IF NOT EXISTS offices TEXT[],
    ADD COLUMN IF NOT EXISTS department_structure TEXT[],
    ADD COLUMN IF NOT EXISTS compliance_preferences JSONB,
    ADD COLUMN IF NOT EXISTS custom_settings JSONB;

CREATE INDEX IF NOT EXISTS idx_organizations_country ON organizations(country);
CREATE INDEX IF NOT EXISTS idx_organizations_industry ON organizations(industry);
CREATE INDEX IF NOT EXISTS idx_organizations_tier_country ON organizations(tier, country);
