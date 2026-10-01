# AIRRAL Agent Notes

These notes are for future coding agents working in this workspace.

## Applicant Portal Design Contract

Before changing `apps/applicant-portal`, read:

- `docs/applicant-portal-design-system.md`
- `docs/applicant-portal-journey-ui-notes.md`
- `docs/applicant-launch-wow-plan.md`
- `docs/job-event-data-sourcing.md`
- `docs/applicant-portal-job-event-data-strategy.md`
- `docs/tomorrow-launch-readiness.md`

The current applicant portal direction is job-first, clean, and mostly white. Launch AIRRAL as a job-market utility first: real jobs, job quality signals, resume-to-job match, and application readiness. Do not rebuild the old dashboard-style experience with profile hero blocks, daily command centers, side rails, many visible cards, green-tinted panels, or a social/feed-first product.

Launch product priority:

- Real active jobs across many industries, not only tech.
- Clear job quality signals: official source, freshness, salary availability, work mode, location, company stability, and application effort.
- Resume-to-job match as the main "wow": match score, missing skills, keyword gaps, weak bullets, and concrete resume fixes for the selected job.
- Application readiness: saved jobs, apply checklist, follow-up reminders, application tracking, and interview prep notes.
- Mobile-first scanning: compact list, readable selected detail, no noisy social/dashboard panels on first load.

Deferred until user feedback proves demand:

- Messaging
- Founder spaces
- Events
- Social feed/community engagement
- LinkedIn-style posting

These can remain as backend foundations or lightly linked support modules, but they should not dominate navigation, first-screen UI, or the launch roadmap.

Use this visual rule (full detail in `docs/applicant-portal-design-system.md`, Theme Contract and Color Rules):

- Light only: a light gray page (`#f4f5f7`), borderless white surfaces, near-black text. No dark theme, dark panels or decorative gradients.
- AIRRAL violet `#5B3DF5` (hover and text `#4A2BD1`, soft fill `#EEEBFF`) is the brand and the one primary action per screen, plus selected states. The logo, favicons and the website accent use it too.
- Color carries meaning, never decoration: the verdict (green Apply, orange Check first, gray Likely skip), scores (green / orange / red), application statuses, and the small colored icon tiles on job facts.
- Use the `--ap-*` tokens in `apps/applicant-portal/src/styles.css`, not hex values.
- Company logos come from `components/company-logo.component.ts`, which falls back to a colored letter tile.

Default applicant journey:

- If matching inputs are missing, show the first-match setup before the job browser. Ask only for target role, location, work mode, salary, skills, and optional resume link.
- Jobs opens first.
- Show jobs before profile data.
- Use a Glassdoor-like split: filters, compact job list, selected job detail.
- Keep job list cards summary-first and cheap to load.
- Show reviews, applicants, interview notes, deeper company insight, resume fit, and application checklist only in the selected job panel.
- Rooms, Messages, Events, and Founder should be hidden/lightweight secondary destinations until the launch job/resume/application loop is strong.

When in doubt, make the UI calmer and more focused. Violet should mean action or selection, not decoration.

## Product Safety Guardrails

- Do not default applicant feed posts to public. Keep the audience selector visible and default to signed-in AIRRAL members.
- Do not display applicant emails, raw user IDs, or internal author IDs in feed cards.
- Treat backend feed data as the source of truth. Local feed cards are only a fallback when the API is unavailable.
- Do not reintroduce arbitrary public ATS detail fetching from the UI. Job details should come from AIRRAL-discovered active postings.

## Company Portal Design Contract

Before changing `apps/hr-portal`, read `docs/hr-portal-design-system.md`. The company portal uses the same light `--ap-*` palette as the applicant portal, shares its buttons, tiles and stage pills from `apps/hr-portal/src/styles.css`, and gives each stage one color on every page.
