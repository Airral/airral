# AIRRAL Workspace Agent Notes

Future agents: when changing the applicant portal UI, work from the frontend design contract first.

Read:

- `airral-frontend/AGENTS.md`
- `airral-frontend/docs/applicant-portal-design-system.md`
- `airral-frontend/docs/applicant-portal-journey-ui-notes.md`
- `airral-frontend/docs/applicant-launch-wow-plan.md`
- `airral-frontend/docs/job-event-data-sourcing.md`
- `airral-frontend/docs/applicant-portal-job-event-data-strategy.md`
- `airral-frontend/docs/tomorrow-launch-readiness.md`
- `airral-frontend/docs/product-completion-agent-plan.md`

Current applicant portal direction:

- Launch focus is job-market utility, not social engagement. AIRRAL should first be the job search OS that finds real jobs, tells the user which roles are worth applying to, and improves the resume/application for that exact job.
- Job-first, not profile/dashboard-first.
- Real job coverage across industries is more important than feed, messaging, founder spaces, or events. Prioritize active roles from official/cached sources, salary/work-mode/freshness/source-quality signals, and broad coverage beyond tech.
- Resume-to-job match is the core "wow" path: upload resume, select a job, show match score, missing skills, weak bullets, keywords, and concrete fixes.
- Application readiness comes before community: save jobs, resume fit, apply checklist, follow-up reminders, application tracking, and interview prep notes.
- White/off-white theme with near-black text.
- AIRRAL teal-green only for primary actions, selected states, brand marks, and success signals.
- Neutral gray borders and white cards by default.
- Glassdoor-like split for jobs: filters, compact list, selected job detail.
- Heavy job data belongs in selected detail, not list cards.
- Mobile-first scanning matters: the applicant should land on useful jobs quickly, with compact controls and no noisy dashboard/social feed.

Do not revive older dashboard/command-center/side-rail UI unless the product direction is explicitly changed.

Deferred product surfaces:

- Messaging, rooms, founder spaces, events, and social/feed engagement are later-stage unlocks. Keep backend foundations if useful, but do not make them the primary nav, first-screen experience, or main product bet until real user feedback proves demand.
- Feed/news should support job decisions only: company changes, hiring signals, layoffs/funding/product shifts, and market context attached to jobs. Do not build a Facebook/TikTok/LinkedIn-style feed as the launch experience.

Product and safety guardrails:

- Job detail endpoints must only live-fetch details for active jobs AIRRAL already discovered and cached. Do not turn public ATS detail routes back into arbitrary Greenhouse/Ashby/Lever proxy endpoints.
- Public feed responses must not expose applicant email addresses or internal author IDs.
- Applicant community posts should default to a signed-in audience, with a visible audience choice before posting.
- Feed queries should show only approved posts; moderation status, length limits, reaction validation, and simple rate limiting are part of the baseline.
