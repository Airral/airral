# AIRRAL Applicant Launch Wow Plan

Last updated: 2026-05-27

Status: active product plan for the applicant portal launch.

## Goal

AIRRAL should feel useful in the first session:

> Upload a resume, find real roles worth applying to, understand whether each role fits your goals and work authorization, fix the application, then track the next step.

The launch product should win on clarity and momentum, not endless scrolling.

## Current Product Audit

What is already strong:

- Applicant login/profile flow exists and creates a candidate profile.
- Resume upload accepts PDF/DOCX, stores a resume document record, hashes the file, parses text/skills, and keeps storage abstract enough for a future GCS provider.
- Job discovery is already separated from tenant HR jobs through `external_companies`, `external_job_sources`, and `external_job_postings`.
- Job list/detail supports cached external jobs from Greenhouse, Lever, Ashby, and SmartRecruiters.
- Job quality fields already exist: `job_quality_score`, `quality_reasons`, `total_comp_label`, and `compensation_confidence`.
- Candidate match preferences already include useful launch inputs such as target roles, location, work mode, salary, skills, relocation, and `needsSponsorship`.
- Public ATS detail lookup is guarded so details can only be loaded for active AIRRAL-discovered cached jobs.

What blocks the wow product:

- The applicant top nav still promotes Feed, Rooms, Messages, Events, and Founder even though launch direction is job/resume/application first.
- Job save is local UI state only; it does not create a backend saved-job/application workspace.
- Resume fit is mostly static UI copy; there is no real selected-job fit endpoint or persisted result.
- Visa support is only a profile flag. Ranking, filters, job cards, and job detail do not yet use visa/sponsorship signals.
- We do not yet ingest H-1B/LCA/PERM sponsor history or model employer visa evidence.
- Job source coverage is broader than before, but still seeded from a small curated list. The target launch path needs hundreds of sources and/or documented aggregator coverage.
- Application checklist, follow-up reminders, and tracking are not yet attached to saved external jobs.

## Visa-First Customer Segment

People on visas are a major AIRRAL customer because they have sharper job-search risk:

- They need to know whether a company has sponsored before.
- STEM OPT candidates need E-Verify confidence and employer training-plan readiness.
- H-1B candidates need transfer/cap timing clarity.
- Some candidates should prioritize cap-exempt employers such as universities, affiliated nonprofits, nonprofit research organizations, and government research organizations.
- Visa candidates need to avoid wasting time on roles that say no sponsorship, contract-only arrangements, staffing ambiguity, or unclear employer-of-record setups.

AIRRAL must not present legal advice. It should present sourced signals and make uncertainty explicit.

## Official Data Sources To Model

Use official sources first:

- U.S. Department of Labor OFLC disclosure data for LCA programs (H-1B, H-1B1, E-3), PERM, worksites, wages, case status, employer names, SOC codes, and locations: https://www.dol.gov/agencies/eta/foreign-labor/performance
- USCIS H-1B Employer Data Hub for employer petition volume and approval/denial signal: https://www.uscis.gov/node/46817
- USCIS H-1B specialty occupation and cap guidance for cap/cap-exempt handling: https://www.uscis.gov/working-in-the-united-states/h-1b-specialty-occupations
- USCIS STEM OPT guidance for E-Verify and Form I-983 expectations: https://www.uscis.gov/working-in-the-united-states/students-and-exchange-visitors/optional-practical-training-extension-for-stem-students-stem-opt
- E-Verify guidance that STEM OPT applicants need the employer's E-Verify company/client company ID on Form I-765: https://www.e-verify.gov/faq/my-employee-asked-for-our-e-verify-company-id-number-so-they-can-apply-for-a-stem-opt-extension

## Product Loop

1. User signs up and uploads resume.
2. AIRRAL asks for target role, location, work mode, salary, and work authorization.
3. Jobs opens first with real active jobs.
4. User turns on filters such as `Visa-friendly`, `H-1B transfer`, `STEM OPT`, `Cap-exempt`, `Salary listed`, and `Official source`.
5. User selects a job.
6. AIRRAL shows:
   - job quality
   - resume fit
   - visa/sponsorship confidence
   - salary/source/freshness/work-mode facts
   - application effort
7. User saves or applies.
8. AIRRAL creates a job workspace with checklist, resume fit result, notes, follow-up reminder, and status.

## UX Priority

Default first screen:

- Top nav: `Jobs`, `Saved`, `Resume`, `Tracker`, `Profile`.
- Feed, Rooms, Messages, Events, and Founder remain hidden, disabled, or behind a `Later`/feature-flag path.
- The search bar should search jobs and companies, not community posts.

Job list:

- Compact, fast, mobile-first.
- Each card shows only scan-critical signals:
  - title/company/location
  - match score
  - salary listed/benchmark needed
  - work mode
  - freshness
  - visa confidence when relevant

Selected detail:

- Primary actions: `Apply`, `Save`, `Check resume fit`.
- Secondary: `Open source`.
- Later/flagged: `Ask room`.
- Main panels:
  - `Why this job`
  - `Visa readiness`
  - `Resume fit`
  - `Salary and source`
  - `Application checklist`
  - `Original posting`

## Data Model Needed

Add or evolve these backend concepts:

### Candidate Work Authorization

Store inside candidate match preferences first, then promote to first-class table if needed:

- `workAuthorizationStatus`: `US_CITIZEN`, `GREEN_CARD`, `H1B`, `H1B_TRANSFER`, `F1_OPT`, `F1_STEM_OPT`, `H4_EAD`, `TN`, `E3`, `O1`, `OTHER`, `UNSPECIFIED`
- `needsSponsorshipNow`
- `needsSponsorshipLater`
- `requiresEVerify`
- `workAuthorizationExpiresAt`
- `openToCapExemptEmployers`
- `openToRelocation`
- `visaNotes`

### Company Immigration Signals

Create `company_immigration_signals`:

- `company_id`
- `normalized_employer_name`
- `h1b_lca_count_recent`
- `h1b_lca_count_total`
- `h1b_uscis_approval_count_recent`
- `h1b_uscis_denial_count_recent`
- `perm_count_recent`
- `top_soc_codes`
- `top_worksite_states`
- `median_lca_wage`
- `latest_lca_filed_at`
- `latest_perm_filed_at`
- `sponsor_confidence_score`
- `cap_exempt_likelihood`
- `everify_status`: `UNKNOWN`, `USER_REPORTED`, `EMPLOYER_CONFIRMED`, `PUBLICLY_CONFIRMED`
- `source_summary`
- `last_refreshed_at`

### Job Visa Signals

Create `external_job_visa_signals` or columns on `external_job_postings`:

- `sponsorship_language`: `SPONSORS`, `NO_SPONSORSHIP`, `AUTHORIZATION_REQUIRED`, `UNKNOWN`
- `visa_confidence_score`
- `visa_reasons`
- `requires_us_work_authorization`
- `contract_or_staffing_risk`
- `stem_opt_risk`
- `h1b_transfer_fit`
- `cap_exempt_fit`

### Saved Job Workspace

Create `candidate_saved_jobs`:

- `user_id`
- `source_job_key`
- `status`: `SAVED`, `APPLYING`, `APPLIED`, `INTERVIEWING`, `OFFER`, `REJECTED`, `ARCHIVED`
- `resume_document_id`
- `fit_result_id`
- `next_step`
- `next_step_due_at`
- `notes`
- `created_at`
- `updated_at`

### Resume-To-Job Fit

Create `candidate_job_fit_results`:

- `user_id`
- `source_job_key`
- `resume_document_id`
- `fit_score`
- `visa_fit_score`
- `matched_requirements`
- `missing_requirements`
- `keyword_gaps`
- `weak_bullets`
- `suggested_rewrites`
- `application_checklist`
- `generated_at`

## Ranking Model V1

Start deterministic:

```
score =
  role_match
+ resume_skill_match
+ location_workmode_fit
+ salary_fit
+ source_trust
+ freshness
+ visa_fit
+ company_sponsor_history
- no_sponsorship_penalty
- contract_staffing_risk
- stale_job_penalty
- missing_salary_penalty
```

If `needsSponsorshipNow` or `requiresEVerify` is true, visa fit should become a first-page ranking factor, not a secondary badge.

## Implementation Plan

### Phase 1: Clean Launch UX

- Hide/deprioritize Feed, Rooms, Messages, Events, and Founder from the applicant top nav.
- Make Jobs the first and strongest view.
- Add `Saved`, `Resume`, and `Tracker` as the main journey destinations.
- Replace `Ask room` in selected job detail with `Check resume fit`.
- Keep company/news signals attached to selected jobs, not as a social feed.

### Phase 2: Server-Backed Job Workspace

- Add `candidate_saved_jobs`.
- Make Save persist to backend.
- Create a saved-job detail/workspace from any external job.
- Add application status, checklist, notes, and follow-up reminder.
- Track source job key, source URL, and selected resume document.

### Phase 3: Real Resume Fit

- Add `POST /api/candidate/jobs/{sourceJobKey}/fit`.
- Compare parsed resume skills/profile against selected job description.
- Return matched requirements, missing requirements, keyword gaps, and suggested rewrite placeholders.
- Persist fit result and attach it to saved job.
- Move the resume tab from static text to real selected-job fit history.

### Phase 4: Visa-Friendly Search

- Add work authorization fields to match setup.
- Add `Visa-friendly`, `H-1B transfer`, `STEM OPT`, and `Cap-exempt` filters.
- Parse job description sponsorship language.
- Add DOL LCA/PERM ingestion jobs and company-level immigration signals.
- Add USCIS H-1B Employer Data Hub ingestion where available.
- Show a `Visa readiness` panel on selected job detail with evidence and uncertainty labels.

### Phase 5: Job Coverage Scale

- Grow official source registry toward 200-500 companies across:
  - healthcare
  - finance/banking
  - retail
  - logistics
  - operations
  - sales
  - government/public sector
  - education
  - manufacturing
  - tech
- Add documented aggregators where licensing/terms are clear.
- Add USAJOBS for federal roles.
- Add source health and admin visibility so dead sources do not create bad UX.

## First Build Slice

The next concrete build should be:

1. Simplify applicant portal navigation to `Jobs`, `Saved`, `Resume`, `Tracker`, `Profile`.
2. Add work authorization inputs to match setup.
3. Add visa signal fields to shared types and backend DTOs.
4. Add deterministic visa scoring from job description language and company sponsor history placeholders.
5. Add persistent saved jobs so the user can leave and come back.

This creates visible value quickly and prepares the database for DOL/USCIS ingestion.

## Success Metrics

Launch metrics should measure useful progress:

- Signup to first real job opened.
- Resume upload to first selected-job fit result.
- Visa filter use to saved job.
- Saved job to apply click.
- Apply click to next-step reminder.
- Day 1 return because a saved job or application task changed.
- Percent of visa-needing users who find at least five credible visa-friendly roles in one session.

Do not optimize for time on site or number of feed cards viewed.
