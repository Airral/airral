# AIRRAL Applicant Portal Design System

Last updated: 2026-09-30

This is the current source of truth for applicant portal UI changes. It supersedes older dashboard and engagement experiments.

## Product Feeling

AIRRAL should feel like a clean job-search workspace: calm, premium, fast to scan, and focused on helping the user choose the next role. The user came to find jobs, not to stare at a dashboard of their own profile.

The closest reference is a cleaner Glassdoor-style job browser, with AIRRAL's advantage inside the selected role: real job quality signals, resume fit, application readiness, and concise context that helps the user decide whether the job is worth applying to.

Launch AIRRAL as:

> The job search OS that finds real jobs, tells users which roles are worth applying to, and improves their resume/application for that exact job.

Do not launch as a social network. Messaging, rooms, founder spaces, events, and feed/community loops are later-stage unlocks after users prove demand through saved jobs, resume checks, applications, and feedback.

## Launch Priority

Focus product work in this order:

1. Real active jobs across many industries, not only tech.
2. Job quality signals: official source, freshness, salary availability, work mode, location, company stability, and application effort.
3. Resume-to-job match: score, missing skills, weak bullets, keyword gaps, and concrete fixes.
4. Application readiness: save job, apply checklist, follow-up reminder, tracker, interview prep notes.
5. Lightweight context: company/news signals only when they help the selected job decision.

Defer:

- Social feed as a primary surface.
- LinkedIn-style posting and engagement.
- Main-nav messaging as a first release pillar.
- Founder spaces and QR groups as a first release pillar.
- Events unless directly tied to application outcomes.

## Theme Contract

Light only. There is no dark theme for the applicant portal.

The palette lives as `--ap-*` custom properties in `apps/applicant-portal/src/styles.css`. Use the tokens, not hex values, in component CSS.

- Page: `--ap-page` `#f4f5f7`
- Surfaces and cards: `--ap-surface` `#ffffff`
- Fills (inputs, segmented controls, soft wells): `--ap-fill` `#eceef2`, `--ap-fill-soft` `#f6f7f9`
- Text: `--ap-ink` `#16181d`, `--ap-ink-2` `#5f6570`, `--ap-ink-3` `#8b919b`
- Hairlines: `--ap-hair`, `--ap-hair-2` (translucent near-black)
- Brand and main action: `--ap-tint` `#5B3DF5`, `--ap-tint-hover` `#4A2BD1`, `--ap-tint-soft` `#EEEBFF`
- Meaning colors: `--ap-green`, `--ap-orange`, `--ap-gray` (verdicts); `--ap-blue`, `--ap-cyan`, `--ap-teal`, `--ap-magenta`, `--ap-red`, `--ap-indigo`, `--ap-pink` (statuses and fact icons)

## Color Rules

Color says something, or it is not used. A gray-only screen was tried and rejected as lifeless; decoration was rejected as noise. The rule in between:

- Violet is the brand and the one primary action on a screen: Apply, Save profile, Check my resume. Selected states use the violet soft fill.
- The verdict has fixed colors everywhere it appears: green = Apply, orange = Check first, gray = Likely skip. It shows as a colored dot and word on job cards and as a tinted answer block on the job detail.
- Scores use the same scale: green is good, orange is getting there, red is weak (resume health tile, profile readiness ring).
- Application statuses have fixed colors: blue saved, cyan applying, teal applied, magenta interviewing, green offer, gray closed. Orange means "needs you now" (due dates, follow-ups).
- The four job facts each carry a small colored icon tile: Pay green, Job orange, Applying blue, Source teal.
- Company logos are real logos where we can find them, and a colored letter tile otherwise (see `components/company-logo.component.ts`). Never a generic globe.

Do not:

- Add a dark theme, dark hero panels, or dark gradients.
- Use gradients, orbs, bokeh or glows as decoration.
- Introduce a color that means nothing, or reuse a meaning color for something else.
- Use teal or green as the brand color: it reads as another green job site. The brand is violet `#5B3DF5`.

## Layout Rules

The Jobs view is the main product surface.

- Top nav is a compact segmented control: Jobs, Applications, Resume, Profile. On phones the same four are a bottom tab bar, and sign-out stays in the header.
- Jobs appears before profile details.
- Desktop: search and quick-filter chips, then a split of a compact job list and the selected job's detail. The full filter panel is collapsed until asked for.
- Phones: the list is the page; a job opens full screen over it with a back button, and the Apply button is fixed at the bottom.
- Job rows are separated by hairlines, not boxed. Surfaces are borderless white on the light page.
- Keep the selected job detail readable and calm, with one Apply action pinned at the bottom of the panel.
- Avoid nested cards and stacked mini-panels. Bordered boxes are only for the two things you act on in detail: resume fit and "Before you apply".
- Radii: 10px for fields and small buttons, 12-16px for cards and panels, full pills for chips and verdict pills.
- No horizontal page scroll at 375px wide. Only the Applications board scrolls sideways, inside its own container.

## Job Data Rules

The list should be cheap and fast:

- title
- company
- location
- posted time
- match score
- salary band
- people who can help
- work mode
- source quality/benchmark labels only when they help the decision

Most users scan in this order:

1. Role title and company
2. Location and remote/hybrid/on-site mode
3. Salary or "salary not listed"
4. Freshness/date posted
5. Requirements fit: seniority, years, skills, visa/location constraints
6. Company trust: reviews, funding/stability, mission, reputation
7. Total compensation signal when available: base salary, bonus, equity/stock, and source confidence
8. Application effort: easy apply vs external apply, resume/checklist needs
9. Benefits and flexibility
10. Hiring process and interview signal

The selected job detail can show heavier data:

- reviews
- applicant count
- interview notes
- company insight
- resume fit
- application checklist
- follow-up reminder
- interview prep notes
- room/event context only when explicitly attached to the selected job and not distracting from apply readiness

Job descriptions should not render as one long employer paragraph. Split them into:

- Quick read
- What you would do
- What they want
- Pay and benefits
- Hiring notes
- Original posting text behind an explicit expand action

Priority facts should be compact text or small chips, not large cards. They are scanning aids, not the main content.

Compensation must distinguish employer-posted salary from market compensation. Do not mix base salary, bonus, and equity into one number without labels. Levels.fyi-style data should be modeled as a benchmark source with company, role family, level, location, base, stock/equity, bonus, total compensation, sample size, and confidence.

Do not show debug/internal copy like "Loaded deeper signal..." to users. If lazy loading is needed, use inline skeletons or subtle loading states inside the selected detail panel.

For more jobs, prefer cursor/load-more browsing over numbered pages. Numbered pages make comparison feel like a search engine; load-more keeps the selected role and list context stable.

The frontend must use the server-backed page endpoint for the primary job feed. It can reveal already-loaded jobs locally first, but once the local batch is exhausted it should request the next page instead of preloading a large hidden result set.

Full descriptions are lazy-loaded only for the selected role. After the backend has cached a description, later opens should read AIRRAL's cache first instead of re-calling the source API.

## Interaction Rules

One filled button per screen or panel. Everything else is a quiet secondary button, a link or an icon button.

Primary actions:

- `Apply on <company>'s site` (external) or `Apply with your AIRRAL profile` (AIRRAL employers)
- `Check my resume` (resume fit for the selected job)
- `Save` (icon button beside Apply)

The job detail reads in this order: company and title, the answer (verdict, reasons, next step), four facts (pay, job, applying effort, source), resume fit, "Before you apply", the description, the full posting behind an expand, sponsorship notes, job quality.

Applications is a to-do list first (Up next, Waiting to hear back, Closed) and a board second. Status, next step, due date and notes are editable in place; a follow-up message can be copied after seven days without a reply.

Error messages say what actually happened. A failed resume check says whether the resume is missing or the posting could not be read, never one catch-all.

## Component Direction

- `app.html` / `app.css`: shell, segmented top nav, phone tab bar.
- `components/company-logo.component.ts`: company logo with fallback (API logo, then the domain's icon, then the company's own careers-site icon, then a letter tile). Skips ATS and airral.com hosts.
- `pages/jobs`: the Jobs browser. The verdict helpers (`getVerdict`, `formatPay`, `getApplyEffort`) live in the component.
- `pages/tracker`: Applications (to-do and board, offers).
- `pages/resume`, `pages/profile`, `pages/onboarding`, `pages/applicant-login`: same palette, borderless surfaces.

Deferred surfaces (feed, rooms, events, founder spaces) stay out of the nav until real demand shows up. Do not reintroduce removed dashboard rails or command center components unless the product direction changes explicitly.

## Connect an AI assistant

A paid feature, reached from a link row on Profile (`/profile/ai`) and shown only when
it is on for the account. No nav item: the nav stays four items. The page wraps the
shared `airral-ai-connect` component (`libs/shared-ui`), which the company portal uses
too. It shows a new key once, from memory only, and lists Claude Code, Claude Desktop
and other MCP apps. The claude.ai website, the phone apps and ChatGPT wait for "Sign in
with AIRRAL".

## Verification Checklist

Before finishing applicant portal UI work:

- Build passes (`nx build applicant-portal`) and lint has no new warnings.
- Jobs screen opens first and shows jobs before any profile data.
- Light only: no dark panels, no decorative gradients.
- Violet is only the brand, the primary action and selection. Other colors match the meanings above.
- Company logos render or fall back to a letter tile, never a blank box or globe.
- No horizontal overflow at 375px on Jobs, Applications, Resume and Profile.
- Heavy job data is absent from list cards and present only in selected detail.
- Resume fit and application readiness are easier to find than messaging, rooms or feed.
- Check signed out and signed in: signed-out save shows the create-account prompt; signed-in save, status change and notes persist after a reload.
