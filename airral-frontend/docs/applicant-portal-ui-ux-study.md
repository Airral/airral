# AIRRAL Applicant Portal UI/UX Study

Last updated: 2026-08-11

## Purpose

This study defines the best near-term UI and user experience for the AIRRAL applicant portal.

AIRRAL should not feel like another profile dashboard, social feed, or generic scraped job board. It should feel like a focused job-search workspace where an applicant can:

1. Find real active jobs quickly.
2. Understand which jobs are worth applying to.
3. Check whether their resume fits the selected job.
4. Save, apply, follow up, and track next steps.

The launch experience should be job-first, mobile-first, calm, and utility-heavy.

## Research Inputs

Primary AIRRAL product sources:

- `airral-frontend/docs/applicant-portal-design-system.md`
- `airral-frontend/docs/applicant-portal-journey-ui-notes.md`
- `airral-frontend/docs/applicant-launch-wow-plan.md`
- `airral-frontend/docs/job-event-data-sourcing.md`
- `airral-frontend/docs/applicant-portal-job-event-data-strategy.md`
- `airral-frontend/docs/tomorrow-launch-readiness.md`

External pattern references:

- Indeed saved jobs and tracker patterns: https://www.indeed.com/help/job-seekers/articles/14087165677837-my-jobs-saving-a-job
- Indeed job preferences and profile-match patterns: https://www.indeed.com/help/job-seekers/articles/32603395892109-adding-or-editing-job-search-preferences
- Indeed My Jobs overview: https://support.indeed.com/hc/en-us/articles/205332490-My-Jobs-Section-Overview
- LinkedIn saved jobs and job tracker pattern: https://www.linkedin.com/help/linkedin/answer/a513247/managing-jobs-you-saved-on-linkedin
- Baymard product list and filtering UX research overview: https://baymard.com/research/ecommerce-product-lists
- Baymard product list UX article collection: https://baymard.com/blog/collections/product-list

## Executive Recommendation

The best AIRRAL portal UI is a cleaner job-search operating system:

- Compact top nav: `Jobs`, `Tracker`, `Profile`.
- Jobs opens first.
- Jobs page uses a split layout on desktop: search/filter/list on the left, selected job detail on the right.
- Mobile uses a single-column flow: search/filter/list first, selected detail as a drill-in panel with a clear back action.
- Job list cards stay compact and scannable.
- Heavy decision data lives in selected job detail.
- Resume fit, save, apply, checklist, and tracker are the main loop.
- Feed, rooms, messages, events, and founder/community surfaces stay out of the primary nav and first-screen experience.

AIRRAL's differentiation should not be "more jobs" alone. It should be:

> This job is real, active, sourced, fresh, relevant to you, worth or not worth your time, and here is what to fix before applying.

## Current-State Scorecard

| Area | Current Direction | Score | Notes |
| --- | --- | ---: | --- |
| Job-first routing | Strong | 9/10 | `/jobs` is the default authenticated route. |
| Primary nav | Good | 8/10 | Jobs, Tracker, Profile is clean. Avoid adding feed/messages back. |
| Job list scanning | Good | 8/10 | Cards show title, company, location, salary, freshness, work mode, match, source signals. |
| Selected job detail | Strong | 8/10 | Good placement for AIRRAL read, resume fit, checklist, quality signals. |
| Mobile mental model | Promising | 7/10 | Drill-in detail pattern is right; needs visual QA on real devices. |
| Filtering UX | Medium | 6/10 | Good filter categories, but filtered pagination and applied-filter clarity need work. |
| Resume-to-job fit | Medium | 6/10 | Real endpoint exists, but fit is keyword/rule-based and should be positioned carefully. |
| Save/apply/tracker loop | Good | 7/10 | Backend persistence exists. Follow-up reminders and checklist editing can be stronger. |
| Trust/source quality | Medium | 6/10 | Signals exist, but UI needs clearer source attribution and confidence labels. |
| Visa support | Early | 5/10 | Useful fields exist, but "Visa-friendly" must not mean "unknown". |
| Social/community restraint | Good | 8/10 | Current routed app is restrained. Old dashboard code remains a drift risk. |

## Best User Journey

### First Visit

The first successful session should feel like this:

1. User signs in or completes onboarding.
2. AIRRAL asks only for launch-critical match inputs:
   - target role
   - location
   - work mode
   - salary expectation
   - key skills
   - work authorization needs
   - resume upload
3. User lands on Jobs, not Profile.
4. AIRRAL shows a list of real active jobs immediately.
5. User selects a job and sees:
   - why AIRRAL is showing it
   - salary/work mode/location/freshness/source facts
   - whether it is worth applying to
   - resume fit action
   - save/apply actions
   - checklist and next step
6. User saves or applies.
7. Tracker shows the job with status, next step, due date, notes, and fit result.

### Returning Visit

The return session should start with momentum:

1. Jobs opens first.
2. If saved jobs need action, show a small tracker badge, not a dashboard takeover.
3. If new matching jobs exist, show a compact "new since last visit" notice.
4. Preserve search/filter context when possible.
5. Let users continue comparing jobs without losing the selected detail.

## Page Architecture

### Top Navigation

Use:

- `Jobs`
- `Tracker`
- `Profile`
- avatar/profile shortcut
- sign out icon

Avoid:

- Feed
- Rooms
- Messages
- Events
- Founder
- large profile hero
- daily command center
- dashboard metrics above jobs

Rationale:

Job seekers are in a high-friction, high-anxiety task. The nav should reduce cognitive load and make the next productive action obvious.

### Jobs Page

Desktop layout:

- top search bar
- filter button and compact active-filter summary
- left list column
- right selected-job detail panel

Mobile layout:

- sticky search/filter row
- compact list cards
- selected job opens as drill-in detail
- clear `Back to jobs`
- apply/save/resume-fit actions visible near the top of detail

Do not place a dashboard, social feed, or generic profile summary above the job list.

## Job Search And Filtering UX

### Search

Search should cover:

- title
- company
- skill keyword
- location
- source tags

Search placeholder:

`Search title, company, skill, or keyword`

Avoid:

- searching community posts from the applicant job search bar
- hiding empty-result recovery
- over-specific default search queries

### Filters

Launch filters should be:

- Work mode: remote, hybrid, on-site
- Posted: 24h, 7 days, 14 days, 30 days
- Salary: salary listed
- Experience: entry, mid, senior, staff+
- Source: official/direct source
- Work authorization: sponsorship mentioned, no sponsorship, unclear

Important change:

Rename `Visa-friendly` to a more precise filter group:

- `Sponsorship mentioned`
- `No sponsorship excluded`
- `Needs review`

Do not label unknown sponsorship as visa-friendly. Unknown can remain visible, but it must be labeled as uncertainty.

### Applied Filter Summary

Show applied filters in a compact row above the list:

- `Remote`
- `Salary listed`
- `Posted 7 days`
- `Sponsorship mentioned`
- `Clear all`

This helps users understand why results changed and recover quickly from over-filtering.

### Empty Results

Empty states should diagnose the likely cause:

- No jobs for this exact search.
- Filters may be too narrow.
- Try removing salary, freshness, or location filters.
- Show a clear `Clear filters` action.

Do not say only `No jobs loaded yet` once the user has actively searched or filtered.

## Job List Card

The list card should stay cheap and compact.

Recommended fields:

1. Company logo or initial.
2. Company name.
3. Posted/freshness label.
4. Role title.
5. Location.
6. Work mode.
7. Salary or `Salary not listed`.
8. Experience/seniority if known.
9. Match score, if personalized.
10. Source quality.
11. Sponsorship signal, if relevant.
12. Easy/direct apply, if true.

Avoid:

- full descriptions
- large company summaries
- reviews
- applicant count
- feed/news snippets
- room/event prompts
- multiple equal-weight buttons

The card should answer:

> Should I open this job detail?

Not:

> Do I fully understand this job?

## Selected Job Detail

The selected detail is AIRRAL's main product surface.

Recommended section order:

1. Header:
   - title
   - company
   - location
   - work mode
   - posted date
   - salary
   - source

2. Primary actions:
   - `Apply`
   - `Save`
   - `Check resume fit`

3. AIRRAL read:
   - Apply if
   - Check before applying
   - Next best move

4. Resume fit:
   - fit score
   - matched requirements
   - missing requirements
   - keyword gaps
   - weak bullets
   - suggested rewrites

5. Application readiness:
   - tailor resume
   - confirm salary/work mode/location
   - check sponsorship if relevant
   - apply from official source
   - set follow-up reminder

6. Job description:
   - Quick read
   - What you would do
   - What they want
   - Pay and benefits
   - Hiring notes
   - Original posting behind expand

7. Source and confidence:
   - source type
   - source URL
   - last seen
   - last updated
   - compensation confidence
   - sponsorship confidence

Avoid:

- long raw employer paragraphs as the first visible detail section
- source/debug language
- large stacked cards inside cards
- secondary social prompts above apply/readiness actions

## Resume Fit Experience

AIRRAL's resume fit should be the "wow" path, but the UI must match the actual model quality.

Use language like:

- `Check resume fit`
- `Resume fit scan`
- `Keyword gaps`
- `Missing requirements`
- `Suggested rewrite`

Avoid overclaiming:

- `AI guarantees`
- `ATS approved`
- `Perfect match`
- `Visa safe`
- `You should apply`

Fit result should show:

- Score
- Matched requirements
- Missing requirements
- Keyword gaps
- Weak bullets
- Concrete rewrite suggestions
- Checklist

Best next step:

Add a stronger rewrite surface:

- Original resume bullet
- Why it is weak for this job
- Suggested rewrite
- Keyword added
- Metric/action/outcome prompt

## Tracker UX

Tracker should behave like a lightweight job CRM.

Statuses:

- Saved
- Applying
- Applied
- Interviewing
- Offer
- Rejected
- Archived

Each tracked job should show:

- title
- company
- status
- resume fit score
- next step
- due date
- notes
- apply/source link

High-impact additions:

- quick status change menu
- follow-up date picker
- next-step suggestions
- sort by due soon
- filter by status

Do not make tracker a generic dashboard. It should be a work queue.

## Trust, Quality, And Source Signals

AIRRAL should make uncertainty visible.

Recommended signal labels:

- `Official source`
- `Direct ATS source`
- `Fresh: seen today`
- `Last seen 2d ago`
- `Salary posted`
- `Salary not listed`
- `Market benchmark needed`
- `Sponsorship mentioned`
- `No sponsorship stated`
- `Sponsorship unclear`
- `External apply`

Avoid vague labels:

- `Good job`
- `Verified` without explaining what is verified
- `Visa-friendly` for unknown evidence
- `Best match` without match reasons

## Mobile UX Rules

Mobile is not a smaller desktop. It is the primary scanning mode.

Rules:

- Search/filter stays reachable at top.
- Cards are compact and vertically scannable.
- Job title and company must appear before badges.
- Salary and location must be visible without opening detail.
- Detail opens as a focused panel/page.
- Top of detail must show `Apply`, `Save`, and `Check resume fit`.
- Back to list is obvious.
- No secondary panels above jobs.
- No horizontal overflow.
- No text trapped in tiny chips.

## Visual Design Direction

Keep:

- white/off-white background
- near-black text
- neutral gray borders
- white cards
- teal-green only for primary actions, selected states, success
- blue only for trust/support signals
- 8px card radius

Avoid:

- green-tinted UI everywhere
- dark theme
- decorative gradients
- glass panels
- nested cards
- oversized hero blocks
- profile-first dashboard panels

## Implementation Priorities

### P0: Fix Trust And Findability

1. Move all filter logic that affects result count into the backend query or fetch enough rows before post-filtering.
2. Show accurate applied-filter chips.
3. Replace `Visa-friendly` with precise sponsorship filters and labels.
4. Ensure selected job detail reads cached AIRRAL details first, then lazy-loads source detail only for active cached postings.
5. Strengthen empty states for no jobs, over-filtering, and backend issues.

### P1: Improve The Wow Path

1. Make `Check resume fit` more prominent than secondary context.
2. Show missing requirements and keyword gaps in selected detail.
3. Add rewrite cards with action/metric/outcome guidance.
4. Attach fit result automatically to saved job.
5. Let the user rerun fit after uploading a new resume.

### P2: Make Tracker Useful

1. Add quick status controls.
2. Add next-step due date.
3. Sort due-soon jobs first.
4. Add status filters.
5. Show fit result and checklist in tracker detail.

### P3: Add Decision Context Carefully

1. Company/news signals only inside selected job detail.
2. Interview hints only when sourced or user-owned.
3. Salary benchmark only when labeled separately from employer-posted pay.
4. People/rooms only as later optional support attached to a job.

## Avoid List

Do not build:

- applicant dashboard as the first screen
- social feed as the main product
- profile hero above jobs
- command center panels
- founder/community/event primary nav
- noisy right rails
- generic career advice feed
- long raw job descriptions as the first detail content
- arbitrary public ATS proxy detail routes
- vague visa-friendly claims

## Success Metrics

Measure whether AIRRAL helps job seekers by tracking:

- time to first useful job open
- search-to-selected-job rate
- selected-job-to-save rate
- selected-job-to-resume-fit rate
- resume-fit-to-apply rate
- saved-job-to-applied conversion
- follow-up reminder creation
- tracker status updates
- filter clear rate after empty results
- mobile list-to-detail completion

Avoid optimizing first for:

- feed scroll time
- community post count
- message count
- event clicks
- vanity profile completion

Those may matter later, but they do not prove the launch job-search loop works.

## Final UX Principle

Every first-screen element should answer one of four questions:

1. What jobs are real and relevant?
2. Which one is worth my time?
3. What should I fix before applying?
4. What is my next step?

If an element does not answer one of those questions, it should be removed, hidden, or moved to a later-stage surface.
