# AIRRAL Applicant Portal Journey Notes

Last updated: 2026-05-27

## Product Thesis

AIRRAL is a job-first workspace. A user comes in to find jobs, decide which jobs are worth applying to, and get the right support around each role. Profile, resume, rooms, messages, events, and founder groups are supporting tools, not the hero of the dashboard.

Current launch thesis:

> AIRRAL should first win by helping users find real active jobs, understand which ones are worth applying to, and fix their resume/application for the exact job.

Do not optimize the first release around social engagement. Messaging, founder spaces, community posts, rooms, and events are future unlocks after users prove the core job/resume/application loop is valuable.

## Default Login Journey

1. Jobs opens first.
2. AIRRAL opens straight into the job marketplace instead of a profile/dashboard hero.
3. The main lane is job matches with only cheap summary data: title, company, location, posted time, match score, salary band, and people who can help.
4. Expensive data such as reviews, interview notes, applicant count, deeper company insight, resume fit, and application checklist should stay out of the list and be shown only in the selected job detail panel.
5. The jobs view should use a cleaner-than-Glassdoor split: filters, compact job list, selected job detail panel, and AIRRAL support inside the selected role.
6. Resume fit and application readiness should be easier to reach than rooms, messages, events, or founder groups.
7. Rooms, Messages, Events, and Founder Groups should not dominate the first screen or primary navigation until user feedback proves demand.

## UX Principles

- Jobs are the main focus; the user should not be forced to stare at their profile every visit.
- Show one primary path: find real jobs, inspect quality signals, check resume fit, apply, track next step.
- Keep job cards summary-first. The list should stay compact, with heavy details in the selected job panel.
- Use rooms and messages only as optional support around a specific job, company, event, or founder group. Do not treat them as the launch product.
- Events should be deferred unless they create direct application outcomes: resume review, interview prep, job fair, company AMA, or follow-up.
- ATS resume help should be tied to a target role, not generic resume advice.
- Founder groups can create private rooms with QR invites later, but they are not part of the launch-critical applicant journey.

## Structure

The dashboard parent owns navigation and journey messaging:

- `candidate-dashboard.component.ts`
- `candidate-dashboard.component.html`
- `candidate-dashboard.component.css`
- `candidate-dashboard.journey.css`

Active feature components:

- `recommended-jobs`: job matches with apply/save/ask-room actions
- `job-rooms`: optional later-stage rooms around companies, roles, interview loops, and founder groups
- `workspace-feed`: optional later-stage peer/news surface, not the primary launch experience
- `career-events`: optional later-stage events that support job search momentum

Removed alternate UI paths:

- Old profile hero, daily command center, hiring radar panel, company trust rail, candidate feed, profile rail, insights rail, engagement dock, onboarding, workspace metrics, and unused application modal pieces were removed or detached so the portal has one focused UI direction.

## Current Engagement Hooks

- Applying to a job keeps the room, resume check, and next step attached to that role.
- Selecting a job is the moment to fetch heavier company/review/interview/applicant data, but that should appear inside the selected job panel, not as a debug-style banner.
- Initial dashboard load should not fetch applications or full job details unless the current view needs them.
- Asking a room is a secondary support path, not the main call to action.
- Creating a room supports company loops, private job circles, founder groups, and QR invites later.
- Messages support peer help later without making the Jobs page feel like a social feed.
- Resume ATS check is attached to the selected target role.
- Founder groups generate a QR-based private group flow later.

## Launch Roadmap Guardrails

Build now:

- Server-backed job feed with active, deduped, official/cached roles.
- Broad source coverage across healthcare, finance, retail, logistics, operations, sales, government, and tech.
- Resume upload and resume-to-selected-job analysis.
- Match reasons, missing skills, keyword gaps, weak bullets, and suggested rewrites.
- Save jobs, application checklist, reminders, and tracking.
- Mobile-first job scanning and selected-detail UX.

Park until feedback:

- Main-nav messaging.
- Founder spaces as a first-class destination.
- Social feed/community posting.
- Events that are not tied to application outcomes.
- Engagement loops designed only to keep users scrolling.

## Design System Direction

Current source of truth: `docs/applicant-portal-design-system.md`.

Company palette:

- Primary: AIRRAL teal-green `#007C6D` for primary actions, selected states, and success signals only
- Secondary: action blue `#3a63d6`
- Warm accent: amber `#b87911`
- Ink: near-black `#111827`
- Surface: `#ffffff`, `#fbfbfa`, `#f6f7f6`
- Border: neutral `#e1e5e9`, not green-tinted by default

Theme ratio: 90% white/black/gray, 8% AIRRAL teal-green, 2% blue/accent.

Angular Material is used for toolbar, buttons, icons, cards, chips, toggles, and progress bars. Local CSS should tune hierarchy, spacing, and brand feeling without fighting Material's accessibility defaults.

## Next Product Work

- Persist selected target role and make all support modules attach to it.
- Replace mock jobs and resume score with API-backed data first.
- Treat rooms, messages, and events as feature-flagged/later-stage modules.
- Use separate APIs: a cheap recommendation summary endpoint for the list, and a lazy job-detail endpoint after job selection.
- Add role-specific ATS scoring with keyword and bullet rewrite suggestions.
- Add save/apply checklist/follow-up reminders/application tracking before expanding social or founder features.
