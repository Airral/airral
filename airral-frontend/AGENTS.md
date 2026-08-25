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

Use this visual rule:

- 90% white / off-white / near-black / neutral gray
- 8% AIRRAL teal-green for primary actions, selected states, and success signals
- 2% blue or accent color for trust/review/support signals

Core theme:

- Background: `#ffffff`, `#fbfbfa`, `#f6f7f6`
- Cards: `#ffffff`
- Main text: `#111827`
- Secondary text: `#4b5563`, `#667789`
- Border: `#e1e5e9`, `#d9dee3`
- Primary teal-green: `#007C6D`
- Dark teal support: `#006B5B`
- Signal blue: `#3a63d6`

Default applicant journey:

- If matching inputs are missing, show the first-match setup before the job browser. Ask only for target role, location, work mode, salary, skills, and optional resume link.
- Jobs opens first.
- Show jobs before profile data.
- Use a Glassdoor-like split: filters, compact job list, selected job detail.
- Keep job list cards summary-first and cheap to load.
- Show reviews, applicants, interview notes, deeper company insight, resume fit, and application checklist only in the selected job panel.
- Rooms, Messages, Events, and Founder should be hidden/lightweight secondary destinations until the launch job/resume/application loop is strong.

When in doubt, make the UI calmer and more focused. Teal-green should mean action or selection, not decoration.

## Product Safety Guardrails

- Do not default applicant feed posts to public. Keep the audience selector visible and default to signed-in AIRRAL members.
- Do not display applicant emails, raw user IDs, or internal author IDs in feed cards.
- Treat backend feed data as the source of truth. Local feed cards are only a fallback when the API is unavailable.
- Do not reintroduce arbitrary public ATS detail fetching from the UI. Job details should come from AIRRAL-discovered active postings.
