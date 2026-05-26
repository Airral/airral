# AIRRAL Applicant Portal Job and Event Data Strategy

Last updated: 2026-05-18

This document captures where AIRRAL should get job/event data and what we need to model. The goal is to support the clean Jobs-first applicant portal without expensive or noisy API calls on every page load.

## Product Position

AIRRAL should not behave like a generic scraped job board. The product advantage is:

- job recommendations matched to the candidate profile
- support around the job: room, people who can help, resume fit, event context
- fast summary cards first, richer job/event detail only after selection

The frontend should call AIRRAL APIs only. External job/event APIs should be ingested by backend jobs, normalized, deduped, cached, and exposed through AIRRAL read models.

## Current Default Feed Rule

The applicant job feed should default to:

- United States roles only.
- Jobs sorted newest first, then older jobs.
- Company and text search should filter across the normalized feed.
- Freshness windows like last 15 days should be optional filters, not the default wall.
- Full job description loaded only when a user selects a job.

Current backend implementation enforces this through:

- `GET /api/candidate/jobs/recommended?source=all&country=US&limit=50`
- Optional `q`, `company`, and `maxAgeDays` query params.
- Source connectors for Greenhouse, Lever, and Ashby.
- Configurable source lists through `GREENHOUSE_BOARD_TOKENS`, `LEVER_SITE_NAMES`, and `ASHBY_BOARD_NAMES`.
- Generic detail route: `GET /api/candidate/jobs/source/{sourceType}/{boardToken}/{jobId}`.

## Recommended Source Strategy

### Jobs

Use a layered source strategy.

1. **First-party AIRRAL / employer jobs**
   - Source: existing HR-side `jobs` table and employer-posted roles.
   - Best for: applications we can own end-to-end, ATS scoring, analytics, employer workflow.
   - Current repo already has `jobs`, `applications`, ATS keywords, salary, department, status, and application tracking.

2. **Targeted ATS public job board APIs**
   - Best for: startup and SaaS roles where we know target companies.
   - Greenhouse Job Board API: public GET endpoints, list jobs, retrieve job, optional `content=true`, optional pay transparency data.
   - Lever Postings API: published postings, company-specific queries, individual posting detail.
   - Ashby public Job Postings API: published postings, location/workplace type, job/apply URLs, compensation when requested.
   - Limitation: these are usually company-board APIs, not global search APIs. We need a target-company registry with Greenhouse board tokens, Lever slugs, Ashby job board names, etc.

3. **Broad job aggregators**
   - Adzuna: broad job ads, keyword/location search, employment data.
   - USAJOBS: federal jobs through an official search API.
   - Optional paid providers later: job data vendors if we need LinkedIn/Indeed/Glassdoor-like coverage without scraping risk.

4. **Avoid as primary sources**
   - LinkedIn: official program is a vetted job posting API, not a public job search feed for our use case.
   - Glassdoor/Indeed-style review/job scraping: high legal and data-quality risk unless we have a licensed provider.
   - Public web scraping should not be the default product plan.

### Events

Use events that create job-search momentum, not random local event noise.

1. **First-party AIRRAL events**
   - Resume review circles, interview prep, application sprints, founder rooms, company AMAs.
   - Best for engagement and conversion because we control room linkage and follow-up.

2. **Luma calendars / owned partner calendars**
   - Good for founder events, startup communities, private group invites, QR-based rooms.
   - Luma supports API/Zapier/iCal/webhooks, so use it for partner calendars or events we manage.

3. **Meetup**
   - Good for professional/community groups if we can get API access and obey its limits.

4. **Ticketmaster Discovery**
   - Good for broad public event discovery by keyword/location/source, but career relevance needs strong filtering.

5. **PredictHQ**
   - Paid global event intelligence. Better for higher-level event signals than candidate event registration.

6. **Eventbrite**
   - Useful for organizer-owned or partner events if we have tokens.
   - Do not rely on broad Eventbrite public search until we verify current access and terms; public event discovery has changed over time.

## Backend Ingestion Pattern

Use this pattern for both jobs and events:

1. Connector runs on schedule or webhook.
2. Store raw payload in a source-specific raw table or JSONB column.
3. Normalize into canonical `external_jobs` / `career_events`.
4. Deduplicate by source id first, then fuzzy keys.
5. Enrich with AIRRAL signals: profile match, room context, people who can help, resume fit.
6. Serve the applicant portal from AIRRAL read models only.

Suggested refresh cadence:

- First-party jobs/events: near realtime.
- ATS public job APIs: every 4-12 hours.
- Broad job aggregators: daily or candidate-triggered saved searches.
- Events: every 6-24 hours, with webhook/iCal sync where available.

## API Shape

Keep the current UI cheap.

### Jobs list endpoint

`GET /api/applicant/jobs/recommendations`

Return only list-card data:

- `id`
- `title`
- `companyName`
- `companyLogoUrl`
- `locationLabel`
- `workMode`
- `postedAt`
- `matchScore`
- `salaryLabel`
- `peopleCanHelpCount`
- `easyApply`
- `sourceType`
- `saved`

Do not include:

- full description
- applicants count
- reviews
- interview notes
- heavy company insights
- raw source payload

### Job detail endpoint

`GET /api/applicant/jobs/{jobId}`

Return selected-panel data:

- all summary fields
- `descriptionPlain`
- `descriptionHtmlSanitized`
- `applyUrl`
- `salaryMin`, `salaryMax`, `salaryCurrency`, `salaryPeriod`
- `skills`
- `requirements`
- `niceToHave`
- `seniority`
- `department`, `team`
- `applicantCount`
- `reviewScore`, `reviewCount` if from licensed or AIRRAL-owned source
- `interviewSignals`
- `companyInsights`
- `roomContext`
- `resumeFit`
- `linkedEvents`
- `sourceAttribution`

### Events list endpoint

`GET /api/applicant/events/recommended`

Return only list-card data:

- `id`
- `title`
- `hostName`
- `startsAt`
- `timezone`
- `format`
- `locationLabel`
- `attendeeEstimate`
- `relevanceScore`
- `primaryAction`
- `reserved`

### Event detail endpoint

`GET /api/applicant/events/{eventId}`

Return selected/full detail data:

- all summary fields
- `description`
- `registrationUrl`
- `venue`
- `onlineUrl` when permitted
- `price`
- `capacity`
- `speakers`
- `agenda`
- `targetRoles`
- `targetCompanies`
- `linkedJobs`
- `roomContext`
- `followUpPrompt`
- `sourceAttribution`

## Canonical Job Model

Suggested normalized fields:

```ts
type JobSourceType =
  | 'AIRRAL_INTERNAL'
  | 'GREENHOUSE'
  | 'LEVER'
  | 'ASHBY'
  | 'ADZUNA'
  | 'USAJOBS'
  | 'MANUAL'
  | 'PARTNER_FEED';

interface CanonicalJob {
  id: string;
  sourceType: JobSourceType;
  externalId?: string;
  externalBoardKey?: string;
  sourceUrl?: string;
  applyUrl?: string;

  companyId?: string;
  companyName: string;
  companyLogoUrl?: string;
  companyDomain?: string;

  title: string;
  normalizedTitle?: string;
  department?: string;
  team?: string;
  seniority?: string;
  employmentType?: 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP' | 'TEMPORARY';
  workMode?: 'REMOTE' | 'HYBRID' | 'ONSITE' | 'UNKNOWN';

  locations: JobLocation[];
  locationLabel: string;

  descriptionPlain?: string;
  descriptionHtmlSanitized?: string;
  requirements?: string[];
  niceToHave?: string[];
  skills?: string[];
  tags?: string[];

  salaryMin?: number;
  salaryMax?: number;
  salaryCurrency?: string;
  salaryPeriod?: 'YEAR' | 'MONTH' | 'HOUR' | 'UNKNOWN';
  salaryLabel?: string;
  salarySource?: 'EMPLOYER_POSTED' | 'AGGREGATOR' | 'AIRRAL_ESTIMATED';

  postedAt?: string;
  updatedAt?: string;
  expiresAt?: string;
  status: 'OPEN' | 'CLOSED' | 'EXPIRED' | 'UNKNOWN';

  rawPayloadHash?: string;
  rawPayload?: unknown;
  dedupeKey: string;
  duplicateGroupId?: string;
  lastSyncedAt: string;
}
```

```ts
interface JobLocation {
  label: string;
  city?: string;
  region?: string;
  country?: string;
  latitude?: number;
  longitude?: number;
  isRemote?: boolean;
}
```

## Job Recommendation / Detail Signals

Keep recommendation signals separate from canonical job facts.

```ts
interface JobRecommendation {
  candidateId: string;
  jobId: string;
  matchScore: number;
  matchReasons: string[];
  skillsMatched: string[];
  skillsMissing: string[];
  salaryFit?: 'UNDER' | 'IN_RANGE' | 'ABOVE' | 'UNKNOWN';
  locationFit?: 'MATCH' | 'FLEXIBLE' | 'MISMATCH' | 'UNKNOWN';
  peopleCanHelpCount: number;
  bestRoomId?: string;
  resumeFitScore?: number;
  generatedAt: string;
}
```

```ts
interface JobDetailSignals {
  jobId: string;
  applicantCount?: number;
  reviewScore?: number;
  reviewCount?: number;
  interviewSignals: string[];
  companyInsights: string[];
  roomContext?: {
    roomId: string;
    roomName: string;
    recentPostCount: number;
    liveNow: number;
  };
  linkedEvents: string[];
  sourceAttribution: DataAttribution[];
  computedAt: string;
}
```

## Canonical Event Model

```ts
type EventSourceType =
  | 'AIRRAL_INTERNAL'
  | 'LUMA'
  | 'MEETUP'
  | 'TICKETMASTER'
  | 'PREDICTHQ'
  | 'EVENTBRITE'
  | 'MANUAL'
  | 'PARTNER_FEED';

interface CanonicalCareerEvent {
  id: string;
  sourceType: EventSourceType;
  externalId?: string;
  sourceUrl?: string;
  registrationUrl?: string;

  title: string;
  description?: string;
  hostName: string;
  hostCompanyId?: string;
  organizerUrl?: string;

  startsAt: string;
  endsAt?: string;
  timezone: string;
  format: 'ONLINE' | 'IN_PERSON' | 'HYBRID';
  venueName?: string;
  locationLabel?: string;
  city?: string;
  region?: string;
  country?: string;
  latitude?: number;
  longitude?: number;

  priceMin?: number;
  priceMax?: number;
  currency?: string;
  isFree?: boolean;
  capacity?: number;
  attendeeEstimate?: number;

  categories: string[];
  targetRoles: string[];
  targetCompanies: string[];
  tags: string[];

  roomId?: string;
  linkedJobIds: string[];
  status: 'SCHEDULED' | 'CANCELLED' | 'POSTPONED' | 'PAST' | 'UNKNOWN';

  rawPayloadHash?: string;
  rawPayload?: unknown;
  dedupeKey: string;
  lastSyncedAt: string;
}
```

## Event Recommendation / Engagement Model

```ts
interface EventRecommendation {
  candidateId: string;
  eventId: string;
  relevanceScore: number;
  reasons: string[];
  linkedJobId?: string;
  linkedCompanyId?: string;
  primaryAction: 'RESERVE' | 'JOIN' | 'ASK' | 'FOLLOW';
  generatedAt: string;
}
```

```ts
interface EventReservation {
  id: string;
  candidateId: string;
  eventId: string;
  status: 'RESERVED' | 'WAITLISTED' | 'ATTENDED' | 'NO_SHOW' | 'CANCELLED';
  reservedAt: string;
  sourceRegistrationId?: string;
}
```

## Suggested Database Additions

Jobs:

- `job_sources`
- `external_jobs`
- `job_recommendations`
- `job_detail_signals`
- `job_saves`
- `job_alerts`
- `company_profiles`
- `company_signals`

Events:

- `event_sources`
- `career_events`
- `event_recommendations`
- `event_reservations`
- `event_followups`

Shared:

- `data_attributions`
- `raw_ingestion_events`
- `dedupe_groups`

## Source Attribution Model

Every externally sourced field that appears in UI should be attributable.

```ts
interface DataAttribution {
  sourceType: string;
  sourceName: string;
  sourceUrl?: string;
  observedAt: string;
  license?: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
}
```

## Implementation Order

1. Add canonical shared types for jobs and events.
2. Add backend tables for sources, canonical jobs, canonical events, recommendations, reservations, and raw payloads.
3. Build internal AIRRAL read endpoints first using current `jobs`, `feed_posts`, and mock events.
4. Add ATS connectors in this order: Greenhouse, Ashby, Lever.
5. Add event connectors in this order: AIRRAL internal, Luma/partner calendars, Meetup, Ticketmaster.
6. Add broad aggregators only after the recommendation and dedupe pipeline is stable.

## Sources Consulted

- Greenhouse Job Board API: https://developers.greenhouse.io/job-board
- Lever Postings API: https://github.com/lever/postings-api
- Ashby Job Postings API: https://developers.ashbyhq.com/docs/public-job-posting-api
- Adzuna API: https://developer.adzuna.com/
- USAJOBS Search API: https://developer.usajobs.gov/api-reference/get-api-search
- LinkedIn Job Posting API terms: https://www.linkedin.com/legal/l/job-posting-api-terms
- Ticketmaster Discovery API: https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
- PredictHQ Search Events API: https://docs.predicthq.com/api/events/search-events
- Meetup API guide: https://help.meetup.com/hc/en-us/sections/41453323105549--Meetup-s-API-User-Guide
- Luma Help Center integrations/API: https://help.luma.com/
