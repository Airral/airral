-- An application made on apply.airral.com carries the resume the applicant had
-- on file when they applied, by document, so the company reviewing it can open
-- it. A resume link on an application HR added by hand stays in resume_url.
ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS resume_document_id BIGINT
        REFERENCES candidate_resume_documents(id) ON DELETE SET NULL;
