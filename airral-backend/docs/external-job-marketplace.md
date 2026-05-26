# External Job Marketplace

AIRRAL applicant jobs are cached in our database. The candidate UI should read AIRRAL's API, not call Greenhouse, Ashby, Lever, or future ATS sources directly.

## Product Rules

- The applicant marketplace is US-only for now.
- Normal job results are newest first.
- Jobs older than 60 days should not stay active in the marketplace unless a source-specific policy says otherwise.
- External jobs are separate from AIRRAL HR tenant jobs. Do not mix this feed into the tenant `jobs` table.
- Job detail can still be loaded lazily from the source when a user opens a role, so list pages stay cheap.
- After a role detail is opened once, cache the description, salary range, quality reasons, and compensation label back to `external_job_postings`.
- Use server-side load-more/cursor-style paging for the applicant jobs API. Do not make the browser fetch a giant list and hide most of it.

## Data Shape

- `external_companies`: one canonical company record, with domain, website, LinkedIn identifiers, headline fields, primary logo URL, brand color, confidence, and active status.
- `external_company_aliases`: names a user/source may use for the same company, for example `DoorDash`, `doordashusa`, or an ATS board slug.
- `external_company_identifiers`: stable external identifiers such as domain, LinkedIn organization id, LinkedIn vanity name, Greenhouse board token, Ashby board name, Lever site, ticker, or future customer-owned ids.
- `external_company_assets`: logos, icons, wordmarks, cover images, and social images with source URL, cached URL, dimensions, variant, source, confidence, attribution, and refresh metadata.
- `external_company_enrichment_runs`: audit trail for company/profile/logo enrichment jobs.
- `external_job_sources`: the ATS source for a company, such as Greenhouse board token or Ashby board name.
- `external_job_postings`: cached active postings shown to applicants.
- `external_compensation_benchmarks`: market compensation sources modeled separately from employer-posted salary. Use this for Levels.fyi-style base, bonus, equity/stock, total compensation, sample size, and confidence.
- `external_job_sync_runs`: lightweight audit trail for scheduled sync health.
- `external_job_sync_locks`: cross-instance lease so only one backend instance syncs at a time.

The unique posting key is `(source_type, source_board_token, external_job_id)`.

## Sync Strategy

- Sync enabled sources every 4 hours.
- Acquire the DB lease before syncing. If another instance owns the lease, skip the run.
- Sync unsynced or oldest-success sources first, with configurable source concurrency and a configurable max source count per run.
- Upsert by source key so repeated pulls do not duplicate jobs.
- Set `expires_at` from the source update date plus the retention window.
- After every sync, mark postings inactive when they are expired, missing a source date, or older than the 60-day retention cutoff.
- The API reads cached postings first. If the cache is empty, live fallback is allowed only for small/narrow source requests. Large source sets must be served from AIRRAL's scheduled cache so one user request never fans out to hundreds of ATS boards.
- The page API is `/api/candidate/jobs/recommended/page`. It returns `jobs`, `limit`, `offset`, `hasMore`, and `nextOffset`.
- Detail loading checks the DB cache first. If no cached description exists, it loads from the source API and writes the detail fields back to AIRRAL.

## Decision Data

Job cards stay small, but every posting should carry enough structured decision data for AIRRAL to help the user choose:

- `job_quality_score`: source-quality score, not candidate fit. Salary, location, freshness, apply link, department, work mode, and cached description improve this score.
- `quality_reasons`: short explainable facts such as `Employer salary listed`, `Location clear`, `Fresh source date`, `Direct apply link`, and `Full description cached`.
- `salary_label`, `salary_min`, `salary_max`, `salary_currency`: employer-posted base salary only.
- `total_comp_label`, `compensation_confidence`: explains whether we only have posted base pay or need benchmark enrichment.
- Compensation benchmark data must not overwrite employer-posted salary. Keep market compensation in `external_compensation_benchmarks` with source, role family, level, location, base, bonus, equity, total comp, sample size, and confidence.

## Production Rules

- Do not hard-delete marketplace jobs in the normal lifecycle. Mark them inactive and keep sync history.
- Add companies through `external_companies` and `external_job_sources`; the frontend should never carry source-specific company lists.
- Do not treat a logo URL as the company record. Logos are assets with source, confidence, expiration, and cache status.
- Keep a denormalized primary `logo_url` on `external_companies` for fast reads, but store all candidates in `external_company_assets`.
- Never scrape LinkedIn pages. Use official APIs only when we have approved access and terms that allow the use case.
- Keep the sync interval and lease duration configurable through environment variables.
- Treat source failures as partial syncs, not full outages. One failed board should not stop other boards from updating.
- Use the sync run table and source `last_error` fields for operations dashboards and alerts.

## Company Logo Source Order

1. Employer upload or AIRRAL customer profile. This is the highest-confidence asset because the company controls it.
2. Licensed brand data API, preferably Brandfetch Brand API or Logo.dev Brand/Logo API, using the verified company domain.
3. Official LinkedIn Organization API only when we have approved API access and the use case is allowed. Store LinkedIn organization id and vanity name as identifiers.
4. Official company website metadata, brand kit, Open Graph image, or favicon. Cache the asset and mark it as website-derived.
5. Favicon fallback, such as Google favicon or Logo.dev/Brandfetch lettermark fallback. This is acceptable for local/prototype display, not enough for polished production.
6. Generated monogram when no trusted asset exists. This avoids broken images and keeps the UI clean.

## Research Notes

- LinkedIn Organization Lookup can return non-admin organization fields such as id, localized name, website, vanity name, locations, and `logoV2`; admin access returns more fields. It is OAuth-protected and should be treated as a partner/permissioned source, not as a public scraping target.
- LinkedIn explicitly prohibits automated crawling/scraping without permission. Do not build logo enrichment by crawling LinkedIn company pages.
- Brandfetch provides Logo API and Brand API lookup by domain/ticker/ISIN/crypto and supports logo variants, sizing, themes, and fallback behavior.
- Logo.dev provides domain-based logos and brand data, with publishable keys for image delivery and secret keys for server-side enrichment.
- Clearbit Logo API is no longer a safe production dependency because it is being shut down; avoid adding new Clearbit usage.

Useful references:

- LinkedIn Organization Lookup: https://learn.microsoft.com/en-us/linkedin/marketing/community-management/organizations/organization-lookup-api
- LinkedIn crawling terms: https://www.linkedin.com/legal/crawling-terms
- Brandfetch Logo API: https://docs.brandfetch.com/logo-api/overview/
- Brandfetch Brand API: https://docs.brandfetch.com/brand-api/overview
- Logo.dev docs: https://docs.logo.dev/
- Clearbit Logo shutdown: https://www.clearbitlogo.com/

## Local Config

Set `BRANDFETCH_CLIENT_ID` to enable Brandfetch logo URLs for companies with verified domains. The frontend embeds those CDN URLs directly in image tags and falls back to AIRRAL monograms if the logo is unavailable.

Use the `local` profile:

```bash
cd /Users/HXS0302/IdeaProjects/airral/airral-backend
./scripts/run-local.sh
```

The local profile runs an initial sync on startup and then repeats every 4 hours.

Seeded sources live in `V7__external_job_marketplace.sql`. Add a new company by inserting one `external_companies` row and one `external_job_sources` row.
