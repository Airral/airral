-- What a company says about itself on its jobs.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS website VARCHAR(255),
    ADD COLUMN IF NOT EXISTS about TEXT;
