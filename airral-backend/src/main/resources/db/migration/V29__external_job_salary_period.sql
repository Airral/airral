-- Pay figures were stored without the unit they were published in.
--
-- salary_min / salary_max hold whatever number the board gave us. Every board
-- also states the interval -- Greenhouse in the range title ("Hourly Rate:"),
-- Lever and Ashby in an "interval" field -- and we dropped it on the floor.
-- An hourly intern rate of $50 therefore sat in the same column as a $250,000
-- annual base with nothing to tell them apart. Readers assumed annual: the job
-- card rendered "USD $0k-$0k" under an "Employer posted" chip, and the job page
-- emitted baseSalary { minValue: 50, unitText: "YEAR" } to Google for Jobs.
--
-- Nullable on purpose. A source that does not state an interval leaves this
-- null, and consumers render the bare amount rather than assuming a unit.
-- Existing rows stay null until the next sync repopulates them.
ALTER TABLE external_job_postings
    ADD COLUMN IF NOT EXISTS salary_period VARCHAR(12);
