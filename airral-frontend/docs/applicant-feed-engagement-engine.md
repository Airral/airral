# AIRRAL Applicant Feed Engagement Engine

Last updated: 2026-05-22

## Product Goal

AIRRAL should feel like the right place to check before applying, interviewing, or choosing a company.

The feed should not be a generic social feed. It should be a career market pulse that combines:

- jobs the user can act on
- company/news signals that explain what changed
- posts from applicants with similar goals
- rooms with useful discussion
- salary and interview intel
- career events and founder/community access
- selected video or external community trends when they improve a job decision

The loop is: **see a relevant signal -> save/follow/ask/apply/comment -> get replies or new matching signals -> come back**.

## Research Notes

Useful patterns from major engagement products:

- LinkedIn ranks around professional relevance, profile/network context, job opportunities, career milestones, and member activity.
  Source: https://www.linkedin.com/help/linkedin/answer/a9554004
- LinkedIn Engineering found dwell time useful, but frames the goal as "time well spent," not raw time spent.
  Source: https://www.linkedin.com/blog/engineering/feed/understanding-feed-dwell-time
- TikTok's For You system learns from actions such as watch behavior, likes, comments, follows, shares, skips, captions, sounds, hashtags, language, country, and device settings.
  Source: https://newsroom.tiktok.com/how-tiktok-recommends-videos-for-you
- YouTube Data API supports `videos.list` with `chart=mostPopular`, `regionCode`, and `videoCategoryId`. It also supports search by `q`, `publishedAfter`, `regionCode`, `relevanceLanguage`, and `safeSearch`.
  Sources: https://developers.google.com/youtube/v3/guides/implementation/videos and https://developers.google.com/youtube/v3/docs/search/list
- Reddit listing endpoints support pagination with `after`, `before`, and `limit`; subreddit feeds include `new`, `rising`, `top`, and `controversial`.
  Source: https://www.reddit.com/dev/api/
- Stack Exchange search supports tags and sorts such as activity, creation, votes, and relevance, useful for developer/technical role signals.
  Source: https://api.stackexchange.com/docs/search
- GitHub search supports repository qualifiers such as stars, pushed date, language, and topic, useful for trend and skill signals.
  Source: https://github.com/github/docs/blob/main/content/search-github/searching-on-github/searching-for-repositories.md
- Product Hunt API v2 is GraphQL and exposes posts/products, topics, comments, votes, and makers with read-only default app scope.
  Source: https://www.producthunt.com/v2/docs
- NewsAPI can search across publishers by query, domains, sources, dates, language, sort, image URL, title, description, and published time.
  Source: https://newsapi.org/docs/endpoints/everything
- GDELT DOC 2.0 supports queryable global news coverage, timelines, relevant article lists, JSON output, and topic volume.
  Source: https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/

## Product Pillars

### 1. For You Career Feed

Personalized blend of:

- jobs matching target role, skills, salary, location, and work mode
- company signals for companies in recommended/saved jobs
- applicant posts from people with similar targets
- rooms connected to saved companies, applications, or events
- one or two high-signal external items such as video, GitHub, Product Hunt, HN, Reddit, or Stack Overflow

### 2. Following

Lower-noise feed from:

- followed companies
- followed topics and roles
- rooms the user joined
- people the user follows
- saved jobs and watched companies

### 3. Market Pulse

Trending but still career-focused:

- "AI product roles are heating up"
- "Backend infra repos and jobs are moving"
- "YC fintech hiring signal"
- "Companies with recent funding and open jobs"
- "Interview prep videos trending for system design"

### 4. Rooms And People

Social engagement should live around work:

- ask about a company
- share interview notes
- ask for referral
- compare offers
- find people applying to the same company

### 5. Action Surface

Every feed card should have at least one useful next action:

- Save
- Follow company/topic
- Ask room
- Apply
- Comment
- Helpful
- Not interested
- Why this?

## Source Strategy

### Tier 0: AIRRAL-Owned Signals

Use these first because they are fast, safe, and unique:

- candidate profile and match preferences
- job saves, hides, searches, applications, and selected job detail opens
- feed posts, reactions, comments, follows, reports
- room joins, replies, active discussions
- resume check target role
- event reservations
- company follows

### Tier 1: Job And Company Signals

Best sources:

- AIRRAL employer-posted jobs
- normalized ATS jobs already discovered by AIRRAL: Greenhouse, Lever, Ashby
- scheduled external job sync into AIRRAL read models
- company news from GDELT, Google News RSS, publisher RSS, and optionally NewsAPI/Bing News with API keys
- licensed company/funding data later: Crunchbase, PitchBook, Tracxn, Harmonic, People Data Labs, Clearbit-style enrichment

Do not make the applicant request path call arbitrary external job details.

### Tier 2: Community And Technical Trend Signals

Use as enrichment, not the main feed:

- Reddit: curated subreddits for jobs, CS careers, recruiting, resumes, specific tech stacks, startup communities
- Hacker News / Algolia: startup, hiring, AI, company/product discussion
- Stack Exchange: questions by skills and role tags
- GitHub: repositories by stars, recent pushes, topics, languages, issues, releases
- Product Hunt: launches, topics, votes, comments, makers

### Tier 3: Video And Learning Signals

Use only when it helps the job journey:

- YouTube most popular by region/category for broad trend awareness
- YouTube search for role-specific topics: "system design interview", "frontend interview", "AI engineer portfolio", "resume ATS"
- rank videos by recency, channel trust, duration, engagement, and role relevance

Avoid turning AIRRAL into a generic video app. A video card should answer: "Will this help me apply, interview, or choose a company?"

### Tier 4: Events

Use:

- AIRRAL-owned events first
- partner calendars and company events
- Luma/Meetup/Eventbrite/Ticketmaster/PredictHQ only where access and terms are clear
- iCal/webhooks for partner-owned calendars

## System Architecture

### Never Build The Feed From Live API Calls

The applicant feed endpoint should be fast because it reads AIRRAL's own database.

Request path:

1. `GET /api/applicant/feed?tab=for_you&cursor=...`
2. Load candidate profile and follow graph.
3. Read pre-normalized `feed_items`.
4. Join recent interaction features.
5. Rank, diversify, and return within the response budget.

External API calls belong in scheduled ingestion workers, not the page request.

### Proposed Backend Modules

- `SourceRegistry`
  Stores source type, credentials ref, rate limit, refresh cadence, enabled flag, trust tier.

- `FeedIngestionScheduler`
  Runs source connectors by cadence and priority.

- `FeedConnector`
  Interface for GDELT, RSS, YouTube, Reddit, HN, Stack Exchange, GitHub, Product Hunt, events, and internal AIRRAL sources.

- `FeedItemNormalizer`
  Converts all source payloads into one canonical shape.

- `FeedItemStore`
  Upserts canonical items by source key and canonical URL/entity key.

- `FeedRankingService`
  Produces ranked feed pages for a viewer.

- `FeedInteractionService`
  Captures impressions, clicks, saves, hides, follows, reactions, comments, applies, and room joins.

- `FeedSafetyService`
  Moderation, visibility, quality checks, author privacy, reporting, and rate limits.

### Canonical Feed Tables

`feed_sources`

- id
- source_type
- display_name
- trust_tier
- refresh_interval_minutes
- timeout_ms
- rate_limit_per_minute
- enabled
- last_synced_at
- last_status

`feed_items`

- id
- source_id
- item_type: `JOB`, `NEWS`, `POST`, `ROOM`, `EVENT`, `VIDEO`, `TECH_TREND`, `PRODUCT_LAUNCH`, `SALARY_INTEL`, `INTERVIEW_INTEL`
- source_external_id
- canonical_key
- title
- summary
- source_url
- image_url
- author_label
- company_id
- company_name
- role_family
- location
- tags
- published_at
- freshness_score
- source_trust_score
- quality_score
- moderation_status
- visibility
- payload_json
- created_at
- updated_at

`feed_item_entities`

- feed_item_id
- entity_type: `COMPANY`, `JOB`, `ROLE`, `SKILL`, `TOPIC`, `LOCATION`, `ROOM`, `EVENT`
- entity_id
- label
- confidence

`feed_impressions`

- user_id
- feed_item_id
- feed_tab
- rank_position
- cursor_id
- seen_at
- dwell_ms
- viewport_percent

`feed_interactions`

- user_id
- feed_item_id
- interaction_type: `CLICK`, `SAVE`, `HIDE`, `NOT_INTERESTED`, `FOLLOW`, `COMMENT`, `REACTION`, `APPLY`, `ASK_ROOM`, `SHARE_ROOM`
- value
- created_at

`feed_user_preferences`

- user_id
- followed_companies
- followed_topics
- hidden_companies
- hidden_topics
- preferred_feed_mix

## Ranking Model V1

Start deterministic, then learn from behavior.

```
score =
  0.28 * profile_role_match
+ 0.18 * company_or_job_match
+ 0.14 * source_trust
+ 0.12 * freshness
+ 0.10 * engagement_quality
+ 0.08 * social_proximity
+ 0.06 * action_value
+ 0.04 * novelty
- 0.25 * seen_recently
- 0.35 * hidden_or_negative_feedback
- 0.20 * low_quality_or_spam
```

### Positive Signals

- user saved a job from that company
- user follows company/topic/room/person
- users with similar role saved/commented
- the item connects to a live job
- the source is trusted
- comments are useful and current
- the item has a clear action

### Negative Signals

- user hid similar source/topic/company
- low source trust
- duplicate story
- stale or already seen
- misleading title
- no career relevance
- too many items from same source

### Diversity Rules

Each page should roughly blend:

- 35-45% jobs and job-adjacent company signals
- 20-25% people/community posts
- 10-15% rooms or discussions
- 10-15% news/company signals
- 5-10% events/videos/trends

No more than:

- 2 consecutive news cards
- 1 video card per first page
- 2 cards from the same external source per page
- 3 cards about the same company per page unless the user follows that company

## Fast Fetching Requirements

Feed endpoint target:

- p50 under 200ms from database/cache
- p95 under 700ms
- hard timeout under 1500ms
- cursor pagination only
- stale-while-revalidate for cached sections

Ingestion connector target:

- independent source timeouts
- circuit breakers per source
- per-source cache TTL
- dedupe before insert
- never block the feed if a source fails
- record source health for admin/debugging

Frontend target:

- open the Jobs view first
- lazy-load Feed only when the tab opens
- show skeleton immediately
- progressively render already-ranked cached items
- prefetch next cursor after idle time, not during dashboard boot
- optimistic reactions/saves with rollback

## UX Direction

### Feed Tabs

- `For You`: mixed ranked feed
- `Following`: companies, rooms, topics, people followed
- `Rooms`: active discussions and replies
- `News`: company/news signals
- `Videos`: optional learning/trend items

### Card Types

- Job pulse card
- Company news card
- Applicant question card
- Interview note card
- Salary signal card
- Room activity card
- Event card
- Video learning card
- GitHub/Product Hunt/HN trend card

### Engagement Controls

Every card should support:

- one primary action
- save/follow
- helpful reaction
- comment or ask room
- hide/not interested
- why this

## MVP Build Sequence

### Phase 1: Strong Feed Core

1. Rename News surface to Feed while preserving Jobs as the default first view.
2. Create `feed_items`, `feed_impressions`, and `feed_interactions`.
3. Materialize current news, community posts, rooms, jobs, and events into `feed_items`.
4. Build `GET /api/applicant/feed`.
5. Add impressions, saves, hides, follows, and helpful reactions.
6. Render mixed feed with existing design system.

### Phase 2: Better Data

1. Move news live fetching fully behind scheduled ingestion.
2. Add source registry and source health.
3. Add YouTube connector for role-specific learning videos.
4. Add GitHub/HN/Product Hunt connectors for tech/company/product trends.
5. Add Reddit connector only for curated communities and only with strong quality filters.

### Phase 3: Personalization

1. Add deterministic ranking from profile, follows, interactions, and job activity.
2. Add "Why this?" explanations.
3. Add "More like this" and "Not interested."
4. Add digest notifications for replies, new matching jobs, saved companies, and rooms.

### Phase 4: Learning Loop

1. Track dwell and viewport impressions.
2. Train simple per-user/topic weights from interactions.
3. Add collaborative signals from similar users.
4. Add A/B tests around feed mix and card order.

## Product Guardrails

- Do not optimize for endless scrolling.
- Optimize for career actions and return value.
- Do not expose applicant emails or internal user IDs.
- Default applicant-authored posts to signed-in visibility.
- Show only approved posts in feed queries.
- Respect source terms and licensing.
- Keep external content summarized and attributed.
- Keep Jobs first; the feed supports job decisions.

