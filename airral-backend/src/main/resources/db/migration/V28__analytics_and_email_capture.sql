-- V28: Be able to answer "did anyone come, and where from".
--
-- There was no analytics of any kind on any of the four apps, so the only
-- evidence a visit had happened was a line in a Cloud Run request log. That is
-- fine for counting requests and useless for counting people, referrers, or
-- where someone gave up.
--
-- First-party rather than a third-party tag, for three reasons. The audience is
-- software engineers, who block Google Analytics at a rate that would make the
-- numbers actively misleading. Same-origin requests are not blocked. And nothing
-- here sets a cookie or stores an address, so there is no consent banner to add
-- to a site whose whole pitch is a lack of friction.

CREATE TABLE IF NOT EXISTS analytics_events (
    id              BIGSERIAL PRIMARY KEY,
    event_name      VARCHAR(60)  NOT NULL,
    path            VARCHAR(500),
    -- Host only. The full referring URL can carry a search query, which is
    -- somebody's private business and is not needed to know a visitor came from
    -- Reddit.
    referrer_host   VARCHAR(255),
    -- A salted daily hash of address and user agent. Enough to count one person
    -- once in a day; useless for following them across days, because the salt
    -- rotates. No address is stored.
    visitor_key     CHAR(64),
    app             VARCHAR(40),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- The two questions this table exists to answer: how many a day, and from where.
CREATE INDEX IF NOT EXISTS idx_analytics_events_day
    ON analytics_events (created_at DESC, event_name);
CREATE INDEX IF NOT EXISTS idx_analytics_events_visitor
    ON analytics_events (visitor_key, created_at DESC);

COMMENT ON COLUMN analytics_events.visitor_key IS
    'Daily salted hash of address + user agent. Rotates every day by design, so it counts unique visitors per day and cannot follow anyone between days.';

-- Most people who arrive will not create an account on the first visit, and
-- until now they left no trace at all. An address is the cheapest way to be able
-- to tell someone the thing they were looking for now exists.
CREATE TABLE IF NOT EXISTS email_signups (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    -- Which surface it came from, so it is possible to tell what is working.
    source          VARCHAR(60),
    referrer_host   VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT email_signups_email_unique UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_email_signups_created ON email_signups (created_at DESC);
