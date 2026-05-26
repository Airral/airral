# AIRRAL Applicant User Journey

Last updated: 2026-05-19

This is the product journey guide for AIRRAL's applicant portal. Use it when designing onboarding, engagement, empty states, navigation, job detail, rooms, resume tools, events, and founder access.

## Product Promise

AIRRAL should feel worth the user's time in the first session.

The promise is not "browse more jobs." The promise is:

> Find roles worth applying to, understand the hidden signals, and move one job forward with people and tools beside you.

The user came to make progress on their job search. Every first-session screen should answer one of these questions:

- Is there a real role here for me?
- Is this job worth my time?
- What should I do next?
- Can someone help me before I apply?
- Will AIRRAL remember my work and reduce the mess next time?

## Research Rules

- Avoid forced product tours. Nielsen Norman Group notes that tutorials interrupt users, are often skipped, and are quickly forgotten.
- Prefer contextual help. Show guidance when the user is doing the thing, not before they know why it matters.
- Use progressive disclosure. Show the few most important options first, then reveal deeper tools when the user asks or shows intent.
- Job seekers care most about transparency and effort. Monster's 2026 research found salary clarity, unpaid assignments, company reputation, unclear job descriptions, unrealistic requirements, and long application processes are major reasons people skip roles.

Sources:

- Nielsen Norman Group, Progressive Disclosure: https://www.nngroup.com/articles/progressive-disclosure/
- Nielsen Norman Group, Onboarding Tutorials vs. Contextual Help: https://www.nngroup.com/articles/onboarding-tutorials/
- Monster, Job Search Deal-Breakers Report, May 8, 2026: https://www.monster.com/career-advice/research/job-search-dealbreakers

## Core Journey

### 1. Create Account

Goal: get the user into useful jobs quickly.

Ask only what we need to personalize the first role list:

- Email or social sign-in
- Name
- Target role or resume upload
- Location preference
- Work mode preference
- Salary expectation, optional

Do not force a full profile before showing value. Resume upload should be the fastest path because it lets AIRRAL infer role, skills, seniority, and likely matches.

## Profile Capture Strategy

We do capture applicant profile data. The key is to capture it in layers so the user does not feel like AIRRAL is blocking them with paperwork.

### Layer 1: Required To Start

Capture during account creation:

- Email
- Name
- Password or social login identity
- Applicant role/user type

This creates the account and lets the user return.

### Layer 2: Required For Good Job Matching

Capture immediately after signup, before the first job feed:

- Resume upload or target role
- Location preference
- Remote/hybrid/on-site preference
- Employment type
- Optional salary expectation
- Work authorization/US-only relevance when needed

This should feel like setup for better jobs, not a profile chore.

### Layer 3: Inferred From Resume

If the user uploads a resume, AIRRAL should extract and prefill:

- Current title
- Seniority
- Skills
- Experience
- Education
- Companies worked at
- Preferred job families
- Likely salary band
- ATS/resume fit baseline

Always let the user confirm or edit inferred fields.

### Layer 4: Captured In Context

Capture more information only when it helps the current action:

- When running resume fit: ask for target role or resume version.
- When applying: ask for phone, links, work authorization, or application-specific answers.
- When joining rooms: ask for role/company interests.
- When saving jobs: learn company, salary, location, and work-mode preferences.
- When creating founder/private rooms: ask for group purpose, invite type, and privacy level.

### Layer 5: Profile Completion Later

Profile completion should live in the profile/resume area, not as the top dashboard hero.

Fields that can wait:

- Bio/headline
- Avatar
- Portfolio links
- LinkedIn/GitHub/website
- Detailed education edits
- Detailed experience edits
- Video intro
- Public visibility settings

The rule: never ask for profile data unless AIRRAL can immediately explain how it improves matches, resume fit, rooms, or applications.

### 2. First Personalization

After account creation, show a short setup panel with one clear message:

> We found roles from your profile. Pick one to move forward.

The first screen should show:

- Three strongest job matches
- Salary/benchmark signal
- Location/work mode
- Posted freshness
- One reason each role is worth attention
- One primary CTA: `Review role`

Do not show profile completion as the hero. Profile completion is support information, not the user's mission.

### 3. First Role Review

When the user opens a role, AIRRAL should summarize what matters:

- Salary: posted base pay or benchmark needed
- Location and work mode
- Freshness
- Company trust
- Application effort
- Resume fit
- People/rooms who can help

Long employer descriptions should stay structured:

- Quick read
- What you would do
- What they want
- Pay and benefits
- Hiring notes
- Original posting text behind expand

### 4. First Meaningful Action

Activation should happen when the user does one of these:

- Saves a target role
- Runs resume check against one role
- Asks a room about one role/company
- Applies and tracks next step

AIRRAL should guide toward one action, not present every feature equally.

Best first action CTA order:

1. `Review role`
2. `Check resume fit`
3. `Ask room`
4. `Apply`
5. `Track next step`

### 5. Return Loop

The returning-user homepage should answer:

> What changed since I was here?

Show:

- New roles since last visit
- Replies in rooms
- Saved role changes
- New salary/company/interview signals
- Applications that need a next step
- Events connected to saved companies or target role

Avoid generic streaks. If we use momentum, make it about useful progress:

- `2 roles reviewed this week`
- `1 resume improved`
- `3 companies watched`
- `1 room reply waiting`

## Guidance Pattern

Use contextual guidance, not a forced welcome tour.

### Use These

- Optional `Show me how AIRRAL works` walkthrough in help/menu.
- Compact onboarding checklist with 3 steps:
  - Upload or confirm resume
  - Pick target role and preferences
  - Move one role forward
- Inline helper text in empty states.
- Tooltips only for unfamiliar concepts:
  - Match score
  - Salary benchmark
  - Room
  - Resume fit
  - Company signal
- Coach marks only after intent:
  - User opens first job detail.
  - User clicks salary benchmark.
  - User tries to ask a room.

### Avoid These

- Full-screen onboarding carousel.
- Five-step tooltip tour on first login.
- Modal that blocks the job list.
- Explaining obvious UI such as search, save, or nav.
- Showing profile completion as the main first-session task.

## Engagement Loops

AIRRAL engagement should be built from job-search relief, not noise.

### Loop 1: Fresh Roles

Trigger: new jobs matching target role, location, salary, or company.

Action: review 3 new roles.

Reward: "Worth applying" shortlist with salary/location/company signals.

Investment: save, hide, apply, or ask room to improve future ranking.

### Loop 2: Room Help

Trigger: user saves or reviews a job with uncertain salary, interview loop, or company signal.

Action: ask one focused question.

Reward: peer answer, salary intel, interview note, or recruiter timing.

Investment: follow room, save answer, add company to watchlist.

### Loop 3: Resume Fit

Trigger: user opens a high-match role.

Action: run resume fit.

Reward: role-specific fixes, not generic resume advice.

Investment: improved resume and a higher confidence apply action.

### Loop 4: Application Tracking

Trigger: user applies externally.

Action: mark as applied or import confirmation.

Reward: clear next step and reminder.

Investment: job history, company watch, interview notes.

### Loop 5: Events and Founder Groups

Trigger: saved company, target role, or founder/admin creates a private room.

Action: reserve event or join QR room.

Reward: warm context and people attached to the job search.

Investment: attendee room, follow-up notes, saved contacts.

## First-Session UX Blueprint

1. User signs up.
2. AIRRAL asks for resume or target role.
3. AIRRAL asks for location/work mode and optional salary.
4. AIRRAL lands on Jobs, not profile.
5. User sees 3 best matches and why.
6. User opens one role.
7. AIRRAL shows priority facts and structured description.
8. AIRRAL offers one next move based on the role:
   - Salary missing: `Get benchmark`
   - Resume unclear: `Check resume fit`
   - Company has room: `Ask room`
   - Strong fit: `Apply`
9. After action, AIRRAL creates a simple job workspace:
   - role
   - room
   - resume fit
   - next step
   - notes
10. On next visit, AIRRAL shows what changed.

## Success Metrics

Track activation and engagement based on meaningful progress:

- Account created to first role opened
- First role opened to saved/applied/room/resume action
- Resume upload rate
- Role-specific resume check rate
- Ask-room rate from job detail
- External apply click rate
- Applied role tracked rate
- Day 1 return with new jobs/replies
- Jobs hidden or saved, used as ranking feedback

Do not optimize only for time on site. A fast, confident apply decision is a win.

## First Social Slice

Implemented direction: applicant-authored career feed posts.

The Feed tab is where users can post:

- Job-search asks
- Career updates
- Interview notes
- Salary intel
- Referral offers

Rules for this surface:

- Keep the feed job-search focused, not generic social media.
- Composer post types should help the user choose intent before typing.
- A post can target a company, job, room, event, or general career topic.
- Public feed reading can be open, but creating a post requires an authenticated applicant.
- The UI may optimistically add a post locally, but the backend source of truth is `/api/feed/community`.
- Do not bury this inside Messages. Messages are private/direct follow-up; Feed is public/community engagement.
