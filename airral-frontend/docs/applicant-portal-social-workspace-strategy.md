# Applicant Portal Social Workspace Strategy

Status: deferred strategy. This document is historical product research for a later social/community layer. It should not drive the current launch build.

Current launch focus:

- Real active jobs across broad industries.
- Fast job search and selected job detail.
- Job quality signals before the user spends application effort.
- Resume-to-selected-job fit and application readiness.
- Saved jobs, application checklist, follow-up reminders, and application tracking.

Messaging, rooms, founder spaces, events, peer feeds, and LinkedIn-style posting are later-stage unlocks after the core job/resume/application loop is strong and user feedback proves demand.

## Product Promise

Future social promise: AIRRAL should not feel like another place to upload a resume and wait. The applicant portal can later become a focused job-search workspace where candidates see hiring momentum, work through applications with peers, join company or role-specific rooms, and keep a clear record of next steps.

The current launch promise is narrower:

**Find real roles worth applying to, improve the resume for that role, and track the next step.**

The later social promise:

**Know where hiring is happening before everyone else, and do not apply alone.**

## Gaps To Fill

Job seekers do not only need more job cards. They need confidence, signal, and companionship through an uncertain process.

- **No trace after applying:** candidates often lose track of where they applied, who responded, what the next step is, and whether silence means rejection.
- **Low feedback and ghosting:** research from Greenhouse and iCIMS points to anxiety, communication gaps, and negative brand impact when candidates do not hear back.
- **Generic professional networking:** broad feeds can make people perform publicly, but job seekers need smaller, safer spaces around roles, companies, interviews, and local events.
- **Weak hiring signal:** candidates want to know which companies are actually growing, recently funded, expanding teams, hosting events, or actively interviewing.
- **Hard-to-start networking:** surveys on networking show many people know networking matters but are unsure where to begin.
- **Salary and trust gaps:** salary transparency increases application intent, and candidates want better signals before spending time on an application.
- **Events are disconnected:** career fairs, meetups, webinars, and hiring events matter, but most job platforms do not connect them cleanly to applications, rooms, prep, and follow-up.

## Research Anchors

- Greenhouse 2024 State of Job Hunting Report: job search anxiety and candidate frustration around communication and hiring process opacity. Source: https://www.greenhouse.com/blog/greenhouse-2024-state-of-job-hunting-report
- iCIMS 2024 Talent Experience Report: candidate communication and experience influence employer perception. Source: https://www.icims.com/wp-content/uploads/2024/10/iCIMS-2024-TX-Report_US_singlepage_092324.pdf
- MIT summary of LinkedIn weak-ties research: weaker professional connections can be useful for job mobility, which supports lightweight networking and room-based discovery. Source: https://news.mit.edu/2022/weak-ties-linkedin-employment-0915
- NACE career fair data: career fairs can lead to interviews and offers, which supports event discovery as a real job-search action, not a side feature. Source: https://naceweb.org/talent-acquisition/student-attitudes/more-than-half-of-students-attended-a-career-fair-in-the-past-12-months
- Indeed salary transparency research: job seekers are more likely to apply when pay ranges are visible. Source: https://www.indeed.com/lead/the-importance-of-fair-pay-and-salary-transparency
- Crunchbase data/API: funding, acquisition, growth, and leadership signals can power a startup hiring radar. Source: https://data.crunchbase.com/

## Differentiation From LinkedIn

AIRRAL should not try to become a broad professional identity network on day one. The first wedge should be a practical, social job-search operating system.

LinkedIn is broad:

- public profiles
- large social feed
- recruiter discovery
- company pages
- general networking

AIRRAL should be focused:

- application memory and next-step tracking
- job rooms around companies, roles, cohorts, and interview loops
- peer feedback on resumes, outreach, interview prep, and offers
- startup funding and hiring-signal radar
- events connected to preparation and follow-up
- trust markers such as salary visibility, recent activity, company signal, and candidate reports

## Engagement Loop

The portal should create a loop that gives users a reason to return even when they are not applying that minute.

1. Candidate sees a hiring signal, event, room, or peer question.
2. Candidate joins a room or asks for feedback.
3. Candidate improves a resume, outreach message, interview answer, or target list.
4. Candidate applies or saves a role with context.
5. Candidate tracks progress and receives next actions.
6. Candidate shares outcome or interview insight back into the room.
7. The room becomes more valuable for the next candidate.

## Deferred UI Direction

When the social layer is unlocked, the applicant portal can make four things visible:

- **Workspace feed:** peer questions, interview notes, resume reviews, offer lessons, and application sprints.
- **Hiring radar:** funded companies, team expansion signals, salary visibility, new hiring events, and confidence tags.
- **Rooms:** smaller social spaces around companies, roles, cities, cohorts, and skills.
- **Events:** career fairs, founder AMAs, recruiter office hours, resume reviews, and interview practice sessions.

For launch, tracking, resume fit, and job quality signals should own the first screen. Social workspace surfaces stay hidden or secondary.

## Early Startup Wedge

AIRRAL can start without big-company partnerships by using public signal and community loops.

- Pick one sharp audience first, such as early-career software engineers, product/frontend engineers, startup operators, or local tech candidates.
- Seed rooms from public data: recently funded companies, posted jobs, public events, accelerator batches, layoffs, and city meetups.
- Offer useful tools before network scale: application tracker, profile score, salary notes, company research cards, event calendar, and peer feedback prompts.
- Create repeat visits with weekly hiring radar, room digests, application sprint reminders, and event follow-up checklists.
- Let candidates contribute lightweight verified insights: interview stage, response time, salary range seen, recruiter responsiveness, and offer outcome.

## Design Principles

- **Action over content:** every card should suggest a useful next move.
- **Small rooms over giant feeds:** candidates need psychological safety and relevance.
- **Signal before volume:** fewer, better opportunities beat endless search results.
- **Traceable progress:** every application, event, room, and conversation should connect back to the candidate's job-search record.
- **Trust through context:** salary, funding, event, and candidate-reported signals should reduce wasted effort.
- **Calm, work-focused UI:** this is a daily workspace, so the interface should be dense, scannable, and direct rather than a marketing page.
- **Clear AIRRAL brand color:** AIRRAL's primary product color is teal-green (`#007C6D`). Use deep navy (`#102436`) for authority and readable contrast. Blue (`#3867d6`) is only a supporting signal color. Coral and amber are small semantic accents, not competing brand colors.
- **Welcoming color system:** avoid a gray admin feel. The first viewport should make the teal/navy brand obvious through the topbar, active navigation, primary action, and hero surface. Keep cards warm-white with restrained shadows and clear hover states so the workspace feels alive without becoming noisy.

## Historical Build Slice

This older frontend slice explored the shape of this experience with mock data:

- Social workspace hero and pulse metrics.
- Peer feed with actionable posts.
- Startup hiring radar.
- Rooms to join.
- Events this week.
- Existing application pipeline and profile tools preserved as support surfaces.

Do not continue this as the default build direction until the core job/resume/application loop is approved and working with real backend data.

## Historical Frontend Structure

If these surfaces return later, keep them modular so future agents can work on one surface without opening the whole dashboard component.

- `candidate-dashboard.component.ts/html/css`: page shell, auth/data loading, navigation, profile form, and application tracker orchestration.
- `models/candidate-dashboard.models.ts`: shared UI-facing contracts for workspace metrics, posts, hiring signals, events, rooms, stages, and actions.
- `data/candidate-dashboard.mock-data.ts`: temporary mock content for the MVP workspace experience.
- `components/workspace-metrics`: pulse stats for rooms, replies, hiring signals, and events.
- `components/workspace-feed`: peer questions, interview intel, application sprints, and social job-search posts.
- `components/hiring-radar`: startup and company hiring signals.
- `components/job-rooms`: focused rooms around companies, roles, cohorts, and events.
- `components/career-events`: events connected to preparation, networking, and follow-up.

As the backend grows, replace launch mock job/resume data first. Social workspace APIs should wait until the core launch surfaces are server-backed.

## Social Pattern Review (May 14, 2026)

Observed patterns from major social platforms and how AIRRAL should apply them:

- **Dual timeline control (`For You` vs `Following`)**  
  Why: X explicitly separates recommendation and follow-only timelines, reducing confusion and giving users agency over discovery intensity.  
  AIRRAL application: feed lens toggle with recommendation transparency and follow-only mode for lower-noise sessions.

- **Quality-weighted ranking, not just clicks**  
  Why: LinkedIn and YouTube both emphasize deeper engagement quality signals (dwell/watch time, comments, saves/shares, satisfaction) over shallow interaction counts.  
  AIRRAL application: highlight quality score, reply depth, saves, and useful discussion prompts.

- **“Why am I seeing this?” explainability**  
  Why: X and Pinterest give users recommendation context and control tools to tune what they see.  
  AIRRAL application: every key card should include a short recommendation reason plus a “less like this” control.

- **Short onboarding that creates immediate identity**  
  Why: Discord documents that short onboarding with role selection reduces drop-off and improves belonging.  
  AIRRAL application: a compact onboarding checklist focused on role, targets, first room, and first event.

- **Recency + discussion sort flexibility**  
  Why: Reddit exposes multiple sorts (`Hot`, `New`, `Top`, `Comment Count`) to match user intent in the moment.  
  AIRRAL application: quick feed sorts (`Quality`, `New`, `Most saved`) to support different search moods.

Reference sources used in this review:

- X Help Center: https://help.x.com/en/using-x/x-timeline
- LinkedIn Engineering (dwell time): https://www.linkedin.com/blog/engineering/feed/understanding-feed-dwell-time
- LinkedIn Help (feed ranking): https://www.linkedin.com/help/linkedin/answer/a9554004
- TikTok Newsroom (For You): https://newsroom.tiktok.com/how-tiktok-recommends-videos-for-you
- YouTube Blog (recommendations): https://blog.youtube/inside-youtube/on-youtubes-recommendation-system/
- Discord docs (community onboarding): https://docs.discord.com/developers/game-development/how-to-create-a-community-for-your-game
- Pinterest Help (home feed): https://help.pinterest.com/en-gb/article/explore-the-home-feed
- Reddit Help (sort controls): https://support.reddithelp.com/hc/en-us/articles/19695706914196-What-filters-and-sorts-are-available

## Agent Handoff Notes

For implementation-level decisions (engagement controls, color lock, component ownership, and next backlog), see:

- `airral-frontend/docs/engagement-design-notes.md`
