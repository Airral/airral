-- Teammates who interview a candidate. Whoever booked the interview is not
-- on it unless they are listed here too.
CREATE TABLE interview_interviewers (
    interview_id BIGINT NOT NULL REFERENCES interviews(id) ON DELETE CASCADE,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (interview_id, user_id)
);

CREATE INDEX idx_interview_interviewers_user ON interview_interviewers (user_id);

-- How long an interview runs, and the time zone of whoever booked it.
-- interview_date stays the wall-clock time they entered, in that zone.
ALTER TABLE interviews
    ADD COLUMN duration_minutes INT NOT NULL DEFAULT 60,
    ADD COLUMN time_zone VARCHAR(64),
    ADD CONSTRAINT interviews_duration_check CHECK (duration_minutes BETWEEN 15 AND 480);
