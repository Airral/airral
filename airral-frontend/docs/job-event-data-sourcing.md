# AIRRAL Job and Event Data Sourcing

Last updated: 2026-05-18

This document captures the current research direction for where AIRRAL should get job and event data, plus what we need to model in the backend.

## Short Answer

AIRRAL should not depend on scraping LinkedIn, Indeed, Glassdoor, or generic search pages for the core product.

## Current Implementation

Multi-source job connector v1 is implemented for the applicant portal.

Backend:

- `GET /api/candidate/jobs/recommended?source=greenhouse&board={boardToken}&limit=20`
- `GET /api/candidate/jobs/recommended?source=all&country=US&limit=50`
- Optional filters: `q`, `company`, and `maxAgeDays`
- `GET /api/candidate/jobs/source/{sourceType}/{boardToken}/{jobId}`
- `GET /api/candidate/jobs/greenhouse/{boardToken}/{jobId}`
- Default board token is configured by `GREENHOUSE_DEFAULT_BOARD`, with a development fallback in backend config.
- Configurable source lists:
  - `GREENHOUSE_BOARD_TOKENS`
  - `LEVER_SITE_NAMES`
  - `ASHBY_BOARD_NAMES`
- Current default feed rule: show US jobs from configured sources, newest first, then older jobs.
- `maxAgeDays` is an optional freshness filter, not the default marketplace wall.
- Connectors call public GET endpoints and normalize them into AIRRAL's candidate job response.
- AIRRAL's candidate job discovery GET endpoints are public/read-only so demo mode can load real Greenhouse jobs without a real applicant JWT.

Frontend:

- `CandidatePortalService.getRecommendedJobs()` calls the AIRRAL feed with `source=all` and `country=US`.
- `CandidateDashboardComponent` maps normalized backend jobs into the existing job browser UI.
- Demo mode keeps the mock applicant profile, then loads live source jobs before falling back to seeded local jobs.
- If the API is unavailable, the dashboard falls back to the local mock jobs so dev/demo does not break.
- External source calls have frontend and backend timeouts so the applicant portal cannot sit forever on the loading screen.

Best first path:

1. Use direct ATS job-board APIs for jobs.
2. Store normalized jobs in AIRRAL.
3. Use cheap job summary data for list views.
4. Lazy-load heavy job detail only after a user selects a job.
5. Use Luma/Eventbrite/Meetup-style sources plus curated partner calendars for career events.
6. Store normalized events in AIRRAL and connect them to companies, roles, rooms, and user follow-up.

## Job Sources

### Best Direct ATS Sources

These are good because they come from the employer's real career system and usually include the official apply URL.

- Greenhouse Job Board API
  - Public GET endpoints do not require authentication.
  - Good for title, location, updated time, description, departments, offices, pay transparency when enabled, and application questions.
  - Source: https://developers.greenhouse.io/job-board

- Lever Postings API
  - Public postings API by site/company name.
  - Good for job postings hosted on Lever.
  - Source: https://github.com/lever/postings-api

- Ashby Public Job Posting API
  - Public job board endpoint by Ashby job-board name.
  - Good for title, location, remote/hybrid/on-site, department/team, description, published date, employment type, job URL, apply URL, and compensation when enabled.
  - Source: https://developers.ashbyhq.com/docs/public-job-posting-api

- SmartRecruiters Posting API
  - Good for companies using SmartRecruiters. Public posting list/detail endpoints expose active published jobs by company identifier.
  - Source: https://developers.smartrecruiters.com/docs/endpoints

- Recruitee Careers Site API
  - Good for Recruitee-hosted career sites.
  - Source: https://docs.recruitee.com/reference/intro-to-careers-site-api

- Workable API
  - Useful for Workable customers to expose open jobs and job detail.
  - Source: https://www.workable.com/developers/

### Aggregator Sources

Use these only when we need broader search coverage quickly. They may require attribution, paid plans, de-duplication, and freshness checks.

- Adzuna API
  - Searches broad job listings by keyword/location and offers job/labor-market data.
  - Source: https://developer.adzuna.com/

- Jooble REST API
  - Searches jobs by keywords/location and returns title, location, snippet, salary, source, type, link, company, updated timestamp, and id.
  - Source: https://help.jooble.org/en/support/solutions/articles/60001448238-rest-api-documentation

### Not A Good First Core Source

- LinkedIn
  - LinkedIn's official Job Posting API is for authorized partners posting jobs to LinkedIn, not open job search ingestion.
  - LinkedIn says it is not accepting new Job Posting API partnerships and access is restricted.
  - Source: https://learn.microsoft.com/en-us/linkedin/talent/job-postings/api/overview?view=li-lts-2025-01

- Glassdoor
  - Glassdoor has partner-style APIs, but it should not be assumed as an open source for job/search/review data.
  - Use Glassdoor as a UX reference, not as the first data source.
  - Source: https://www.glassdoor.com/developer/jobsApiActions.htm

- Indeed
  - Do not rely on an open official job search API for the first version.
  - Prefer ATS/direct-company sources and aggregators with documented access.

## Job Data Model

AIRRAL currently has an internal HR `jobs` table, but the applicant marketplace needs external-source fields and recommendation fields.

Recommended entities:

### `job_sources`

- `id`
- `source_type`: `GREENHOUSE`, `LEVER`, `ASHBY`, `SMARTRECRUITERS`, `RECRUITEE`, `WORKABLE`, `ADZUNA`, `JOOBLE`, `AIRRAL_INTERNAL`
- `company_id`
- `display_name`
- `base_url`
- `board_token` or `site_name`
- `credentials_ref`
- `sync_enabled`
- `sync_interval_minutes`
- `last_synced_at`
- `last_sync_status`

### `companies`

- `id`
- `name`
- `domain`
- `logo_url`
- `industry`
- `size_range`
- `hq_location`
- `source_profile_url`
- `rating_summary`
- `created_at`
- `updated_at`

### `jobs`

Core normalized fields:

- `id`
- `company_id`
- `source_id`
- `external_job_id`
- `external_post_id`
- `canonical_key`
- `title`
- `description_html`
- `description_plain`
- `department`
- `team`
- `role_family`
- `seniority`
- `employment_type`
- `work_mode`: `REMOTE`, `HYBRID`, `ONSITE`, `UNKNOWN`
- `locations`
- `country`
- `salary_min`
- `salary_max`
- `salary_currency`
- `salary_period`
- `apply_url`
- `job_url`
- `status`: `OPEN`, `CLOSED`, `EXPIRED`
- `published_at`
- `updated_at`
- `first_seen_at`
- `last_seen_at`
- `source_payload_hash`
- `source_payload_json`

AIRRAL-enriched fields:

- `skills`
- `tags`
- `ats_keywords`
- `matchable_text`
- `quality_score`
- `freshness_score`
- `salary_confidence`
- `source_confidence`
- `duplicate_group_id`

### `job_recommendations`

Per candidate:

- `id`
- `candidate_id`
- `job_id`
- `match_score`
- `match_reasons`
- `matched_skills`
- `missing_skills`
- `salary_fit`
- `location_fit`
- `work_mode_fit`
- `connections_count`
- `room_id`
- `ranked_at`

### `job_details_cache`

Heavy data loaded only when selected:

- `job_id`
- `review_score`
- `review_count`
- `applicant_count`
- `interview_signal`
- `company_insight`
- `room_context`
- `last_refreshed_at`

## Job API Shape For AIRRAL

Use two levels:

### Cheap list endpoint

`GET /api/candidate/jobs/recommended`

Returns:

- `jobId`
- `title`
- `companyName`
- `locationLabel`
- `workMode`
- `postedLabel`
- `matchScore`
- `salaryLabel`
- `connectionsCount`
- `easyApplyAvailable`

### Lazy detail endpoint

`GET /api/candidate/jobs/{jobId}`

Returns:

- description
- reviews/signals
- applicant count if available
- interview notes/signals
- company insight
- room context
- resume fit
- events connected to this company/role
- apply mode and apply URL

## Apply Rules

Do not label every external job as Easy Apply.

Use:

- `INTERNAL_APPLY`: AIRRAL-owned job; submit with our application API.
- `PARTNER_APPLY`: employer has given API permission for application submission.
- `EXTERNAL_APPLY`: open the company/ATS apply URL.

Greenhouse and SmartRecruiters can support application submission flows, but that usually requires employer credentials/permission. For public job ingestion, default to external apply URL.

## Event Sources

### Best First Sources

- Luma
  - Best for AIRRAL-hosted and partner calendars.
  - Requires Luma Plus on the calendar and a calendar-specific API key.
  - Provides event/calendar management, registrations, analytics, and webhooks.
  - Source: https://help.luma.com/p/luma-api

- Eventbrite
  - Good for public/professional events and organizers.
  - Use official Eventbrite Platform API / SDK access with OAuth.
  - Source: https://github.com/eventbrite/eventbrite-sdk-javascript

- Google Calendar
  - Good for AIRRAL-owned calendars, partner calendars, and internal editorial schedules.
  - Source: https://developers.google.com/workspace/calendar/api/v3/reference/events/list

### Broader Event Discovery

- Meetup
  - Useful for local professional communities, but verify current API access and terms before relying on it.
  - Source: https://www.meetup.com/api/support/

- Ticketmaster Discovery API
  - Good for large public/ticketed events, not the core career-event source.
  - Source: https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/

- SeatGeek Platform API
  - Good for public live events, mostly US/Canada.
  - Source: https://seatgeek.github.io/

- PredictHQ
  - Paid/enriched event intelligence. Useful later for broad market signals and big conferences, not MVP.
  - Source: https://docs.predicthq.com/api/events

## Event Data Model

### `event_sources`

- `id`
- `source_type`: `LUMA`, `EVENTBRITE`, `GOOGLE_CALENDAR`, `MEETUP`, `TICKETMASTER`, `SEATGEEK`, `PREDICTHQ`, `AIRRAL_INTERNAL`
- `display_name`
- `credentials_ref`
- `calendar_id`
- `organizer_id`
- `sync_enabled`
- `last_synced_at`
- `last_sync_status`

### `career_events`

- `id`
- `source_id`
- `external_event_id`
- `title`
- `description`
- `host_name`
- `organizer_name`
- `event_url`
- `registration_url`
- `image_url`
- `format`: `ONLINE`, `IN_PERSON`, `HYBRID`
- `event_type`: `WEBINAR`, `AMA`, `JOB_FAIR`, `NETWORKING`, `PORTFOLIO_REVIEW`, `HACKATHON`, `OFFICE_HOURS`, `CONFERENCE`
- `start_at`
- `end_at`
- `timezone`
- `venue_name`
- `address`
- `city`
- `region`
- `country`
- `lat`
- `lng`
- `capacity`
- `attendee_count`
- `price_min`
- `price_max`
- `currency`
- `status`: `SCHEDULED`, `CANCELLED`, `ENDED`
- `published_at`
- `updated_at`
- `first_seen_at`
- `last_seen_at`
- `source_payload_json`

AIRRAL-enriched fields:

- `target_roles`
- `target_companies`
- `skills`
- `tags`
- `relevance_score`
- `room_id`
- `follow_up_template`

### `candidate_event_actions`

- `id`
- `candidate_id`
- `event_id`
- `status`: `SAVED`, `RESERVED`, `REGISTERED`, `ATTENDED`, `MISSED`
- `registration_external_id`
- `reminder_at`
- `follow_up_status`
- `created_at`
- `updated_at`

## Event API Shape For AIRRAL

### Cheap list endpoint

`GET /api/candidate/events/recommended`

Returns:

- `eventId`
- `title`
- `host`
- `timeLabel`
- `format`
- `eventType`
- `attendeeCount`
- `relevanceReason`
- `actionLabel`

### Detail endpoint

`GET /api/candidate/events/{eventId}`

Returns:

- full description
- registration URL
- venue/online details
- connected companies/jobs
- room link
- attendee signal
- follow-up suggestions

## MVP Recommendation

Jobs MVP:

1. Add external job-source connectors for Greenhouse, Lever, and Ashby.
2. Add Adzuna or Jooble only if we need broad search quickly.
3. Normalize into AIRRAL `jobs`.
4. Build cheap summary endpoint and lazy detail endpoint.
5. Keep external apply as a URL until we have employer/partner apply permission.

Events MVP:

1. Start with AIRRAL internal events plus Luma/Google Calendar partner calendars.
2. Add Eventbrite for broader career/professional events.
3. Add Meetup only after confirming current API access and terms.
4. Use Ticketmaster/SeatGeek/PredictHQ later for large conferences or market signals.

Do not build the frontend around source-specific fields. Normalize first, then let the UI read AIRRAL's own stable job/event models.
