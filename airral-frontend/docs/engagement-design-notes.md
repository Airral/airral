# AIRRAL Engagement Design Notes (May 14, 2026)

Status: historical reference. The current applicant portal direction is documented in `docs/applicant-portal-design-system.md` and `docs/applicant-portal-journey-ui-notes.md`. Future UI work should follow those files, not the older dashboard/command-center direction below.

This file captures product and UI decisions so any future agent can continue without re-discovery.

## 1) Social Patterns Reviewed

We reused these cross-platform patterns because they repeatedly drive engagement:

- X: dual timeline control (`For you` vs `Following`) for user agency.
- LinkedIn + YouTube: quality signals (dwell/watch/satisfaction) matter more than raw clicks.
- TikTok + Pinterest: recommendation transparency plus feed tuning controls.
- Reddit: intent-based sorting (`Hot/New/Top` style controls).
- Discord: short onboarding checklist to reduce early drop-off.

## 2) AIRRAL Design Translation

Implemented in the candidate dashboard workspace:

- Feed lens toggle (`For you` / `Following`).
- Feed sort controls (`Quality` / `New` / `Most saved`).
- Recommendation context on posts (`whyRecommended`).
- Lightweight quality score visualization per post (`depthScore`).
- Compact onboarding checklist in the right rail.
- Hiring signal cards now include `whyNow` and adjustment controls.

## 3) Company Color Decision (Locked)

Primary AIRRAL color system:

- `Primary Teal`: `#007C6D`
- `Deep Teal`: `#0b7c61`
- `Trust Navy`: `#132634`
- `Signal Blue`: `#3a63d6`
- `Warm Coral Accent`: `#e56b56`
- `Warm Amber Accent`: `#b87911`

Usage rules:

- Teal is brand ownership color (topbar active states, primary CTA, key progress).
- Navy carries authority and core text readability.
- Blue is recommendation/signal support, not primary brand.
- Coral and amber remain sparse semantic accents only.
- Surfaces stay warm-white (`#ffffff`, `#f6fbf9`, `#eef7f4`) to avoid a dull gray admin feel.

## 4) Structure and Ownership

To keep implementation modular, workspace UI stays split by responsibility:

- `candidate-dashboard.component.*`: shell, page orchestration, view switching.
- `candidate-dashboard.theme.css`: shared workspace tokens + theme decisions.
- `candidate-dashboard.workspace.css`: hero/workspace layout styling.
- `candidate-dashboard.forms.css`: form-specific styling only.
- `components/*`: isolated social surfaces (`workspace-feed`, `hiring-radar`, `job-rooms`, `career-events`, `engagement-onboarding`, `engagement-dock`, `workspace-metrics`).

## 5) Next Iteration Backlog

High-impact follow-ups:

1. Add per-user feed tuning controls (`less like this`, topic mute persistence).
2. Track action quality (reply depth, save-to-apply conversion) as first-class telemetry.
3. Promote event + room follow-up nudges based on pipeline stage.
4. Add A/B hooks for lens default (`For you` vs `Following`).

## 6) What "Engagement" Means in AIRRAL

In this workspace, engagement is defined as actions that move a candidate forward with other people:

- Marking a post helpful.
- Replying to a peer post.
- Saving a post for follow-up.
- Following a room or hiring signal.
- Joining a room or reserving an event.
- Completing onboarding actions that create search momentum.

Current UI behavior:

- Feed engagement controls are now interactive (helpful/save/follow toggle state updates in-session).
- Feed ranking and lens controls remain user-driven (`For you` / `Following`, `Quality` / `New` / `Most saved`).

## 7) Daily Command Center Redesign

The overview now leads with a calm daily command center and one tabbed workspace instead of many visible panels.

Purpose:

- Give users one obvious daily reason to return.
- Put core engagement actions above the fold without overwhelming the page.
- Let users choose what they want now: feed, company news, rooms, events, or founder access.
- Keep profile strength, best hiring signal, and next step visible but secondary.
- Keep profile work in the Portfolio section instead of mixing it into the daily workspace.

Implementation:

- `components/daily-command-center`: first-viewport command surface using Angular Material card, chips, divider, progress bar, and buttons.
- Overview uses Angular Material tabs to avoid showing feed, rooms, events, company news, and founder access all at once.
- Section surfaces now have distinct roles: command center as home base, tabs as navigation shell, feed as content canvas, company news as blue signal cards, rooms as teal live-community cards, and events as amber calendar cards.
- `workspace-feed`: interactive helpful/save/follow state and Material feed controls.
- `job-rooms`: live room counts plus joined state.
- `career-events`: reserved state.
- Dashboard shell shows snackbar feedback for meaningful actions so clicks feel acknowledged.
