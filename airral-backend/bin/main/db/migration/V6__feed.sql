-- V6: Community feed tables
-- feed_posts     — company-authored posts visible to candidates
-- feed_reactions — per-user reactions on posts (toggle, one reaction type per user per post)
-- feed_comments  — threaded responses to posts
-- company_follows — users following organizations

CREATE TABLE feed_posts (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    post_type           VARCHAR(30) NOT NULL DEFAULT 'COMPANY_SIGNAL',
    -- COMPANY_SIGNAL | HIRING_PULSE | ROLE_SPOTLIGHT | COMMUNITY_TIP

    visibility          VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    -- PUBLIC | AUTHENTICATED | APPLICANTS_ONLY

    topic               VARCHAR(100),
    content             TEXT NOT NULL,

    -- Optional link to a specific job
    linked_job_id       BIGINT REFERENCES jobs(id) ON DELETE SET NULL,

    -- Author inside the org
    author_id           BIGINT REFERENCES users(id) ON DELETE SET NULL,

    published_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE feed_reactions (
    id              BIGSERIAL PRIMARY KEY,
    post_id         BIGINT NOT NULL REFERENCES feed_posts(id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reaction_type   VARCHAR(20) NOT NULL,
    -- USEFUL | INSPIRING | PRACTICAL
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_feed_reaction_user_post UNIQUE (post_id, user_id)
);

CREATE TABLE feed_comments (
    id          BIGSERIAL PRIMARY KEY,
    post_id     BIGINT NOT NULL REFERENCES feed_posts(id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content     TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE company_follows (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_company_follow UNIQUE (user_id, organization_id)
);

-- Indexes
CREATE INDEX idx_feed_posts_org      ON feed_posts(organization_id);
CREATE INDEX idx_feed_posts_type     ON feed_posts(post_type);
CREATE INDEX idx_feed_posts_pub      ON feed_posts(published_at DESC);

CREATE INDEX idx_feed_reactions_post ON feed_reactions(post_id);
CREATE INDEX idx_feed_reactions_user ON feed_reactions(user_id);

CREATE INDEX idx_feed_comments_post  ON feed_comments(post_id);

CREATE INDEX idx_company_follows_user ON company_follows(user_id);
CREATE INDEX idx_company_follows_org  ON company_follows(organization_id);
