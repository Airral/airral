# AIRRAL Workspace Agent Notes

Future agents: when changing the applicant portal UI, work from the frontend design contract first.

Read:

- `airral-frontend/AGENTS.md`
- `airral-frontend/docs/applicant-portal-design-system.md`
- `airral-frontend/docs/applicant-portal-journey-ui-notes.md`
- `airral-frontend/docs/job-event-data-sourcing.md`
- `airral-frontend/docs/applicant-portal-job-event-data-strategy.md`

Current applicant portal direction:

- Job-first, not profile/dashboard-first.
- White/off-white theme with near-black text.
- AIRRAL teal-green only for primary actions, selected states, brand marks, and success signals.
- Neutral gray borders and white cards by default.
- Glassdoor-like split for jobs: filters, compact list, selected job detail.
- Heavy job data belongs in selected detail, not list cards.

Do not revive older dashboard/command-center/side-rail UI unless the product direction is explicitly changed.

Product and safety guardrails:

- Job detail endpoints must only live-fetch details for active jobs AIRRAL already discovered and cached. Do not turn public ATS detail routes back into arbitrary Greenhouse/Ashby/Lever proxy endpoints.
- Public feed responses must not expose applicant email addresses or internal author IDs.
- Applicant community posts should default to a signed-in audience, with a visible audience choice before posting.
- Feed queries should show only approved posts; moderation status, length limits, reaction validation, and simple rate limiting are part of the baseline.
