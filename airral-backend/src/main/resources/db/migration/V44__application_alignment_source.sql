-- What a candidate's job alignment could read: the attached resume and the
-- note, the note alone (a candidate added with a resume link), or the note
-- alone because the attached resume's text could not be read, as with a
-- scanned image. Null for applications made before this was recorded.
ALTER TABLE applications
    ADD COLUMN alignment_source VARCHAR(20)
        CONSTRAINT applications_alignment_source_check
        CHECK (alignment_source IN ('RESUME_AND_NOTE', 'NOTE', 'UNREADABLE_RESUME'));
