# AIRRAL Applicant Portal Design System

Last updated: 2026-05-27

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

Use a white / off-white theme with near-black text.

- Background: `#ffffff`, `#fbfbfa`, `#f6f7f6`
- Card surface: `#ffffff`
- Main text: `#111827`
- Secondary text: `#4b5563`, `#667789`
- Neutral border: `#e1e5e9`, `#d9dee3`
- Primary AIRRAL teal-green: `#007C6D`
- Dark teal support: `#006B5B`
- Soft teal background: `#E7F5F1`
- Signal blue: `#3a63d6`
- Sparse warm accent: `#b87911`

Theme ratio:

- 90% white, off-white, near-black, and neutral gray
- 8% AIRRAL teal-green
- 2% blue or accent color

## Color Rules

Do:

- Use AIRRAL teal-green for primary CTAs, selected states, success states, and brand marks.
- Use near-black headings and body text for strong readability.
- Use neutral gray borders by default.
- Use blue only for trust, review, or signal chips.
- Keep cards white.
- Keep selected job cards flat: use a clean teal-green border, not a raised shadow or glow.

Do not:

- Use a black or dark theme for the applicant portal.
- Turn the page into a green UI.
- Use green-tinted borders and backgrounds everywhere.
- Add decorative gradients, orbs, or bokeh.
- Use color to decorate sections that do not need semantic emphasis.
- Use drop shadows or background glow as the selected-job state.

## Layout Rules

The Jobs view is the main product surface.

- Top nav stays compact.
- Jobs appears before profile details.
- Use a split layout: filters, compact job list, selected job detail.
- Keep the list narrow enough to scan.
- Keep the selected job detail readable and calm.
- Put AIRRAL support hooks inside the selected job detail, not in a noisy top dashboard.
- Avoid nested cards and stacked mini-panels.
- Cards use 8px border radius unless a Material control requires otherwise.
- On mobile, prioritize fast scanning: search, filters, job list, selected detail, and resume/application actions. Avoid secondary panels that push jobs below the fold.

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

Primary actions:

- `Easy apply`
- `Apply`
- `Check resume fit`
- `Save job`

Support actions:

- alerts
- ask room
- reserve event
- create room

Avoid putting too many same-weight buttons in one section. One primary action should be obvious.

## Component Direction

Current candidate dashboard structure:

- `candidate-dashboard.component.*`: shell, top nav, view switching, journey messages for meaningful actions only
- `candidate-dashboard.journey.css`: shared styles for non-job destination pages
- `components/recommended-jobs`: main Jobs browser
- `components/job-rooms`: later-stage support around selected jobs, companies, events, founder groups
- `components/workspace-feed`: later-stage peer/news surface; should not dominate launch UX
- `components/career-events`: later-stage events connected to job outcomes

Do not reintroduce removed dashboard rails or command center components unless the product direction changes explicitly.

## Verification Checklist

Before finishing applicant portal UI work:

- Build passes.
- Jobs screen opens first.
- First screen is not dominated by profile data.
- Main heading is near-black, not teal-green.
- Cards are white.
- Borders are neutral gray.
- Teal-green is reserved for selected/action states.
- No horizontal overflow on desktop or mobile.
- Heavy job data is absent from list cards and present only in selected detail.
- Resume-to-job match and application readiness are easier to find than messaging, founder spaces, or feed.
- Feed/news/rooms do not appear as the main value proposition on first load.
