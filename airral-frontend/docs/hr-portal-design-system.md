# AIRRAL Company Portal Design System

The company (HR) portal uses the same light palette as the applicant portal. It
has to answer one question fast: who is waiting on me, and what do I do next.

## Theme

- Light only. A light gray page (`--ap-page`), borderless white surfaces with a
  hairline shadow, near-black text. No dark panels, including the sign-in page.
- Use the `--ap-*` tokens in `apps/hr-portal/src/styles.css`, not hex values.
- AIRRAL teal (`--ap-tint`) is the brand and the one primary action per screen,
  plus selected states. Everything else is a gray fill button.
- Fields are soft wells: `--ap-fill-soft` with an inset `--ap-hair-2` ring, and a
  2px teal ring on focus.

## Color means something

Each color below means the same thing on every page. Do not use them to decorate.

| Meaning | Color |
| --- | --- |
| New application (`SUBMITTED`) | blue |
| In review (`UNDER_REVIEW`) | cyan |
| Shortlisted | teal |
| Interview scheduled, and interviews anywhere | purple |
| Interviewed: needs your decision | orange |
| Offer extended, hired, accepted, open job | green |
| Draft job, draft offer | orange / gray |
| Declined, rejected, withdraw | red |
| Closed, withdrawn, cancelled | gray |

## Shared primitives

`apps/hr-portal/src/styles.css` defines these. Pages use them and do not
restyle buttons or pills themselves:

- Buttons: `.btn` plus `.btn-primary`, `.btn-secondary` (or `.btn-quiet`),
  `.btn-danger` and `.btn-sm`. Round `.icon-btn` for refresh and close.
- Glance tiles: `.glance` holding `.tile.t-blue` / `.t-purple` / `.t-green` /
  `.t-teal` / `.t-orange`, each a label `span` and a number `strong`. Two per row
  on phones.
- Stage pill: `.stage[data-stage="<ApplicationStatus>"]`.
- `.alert` for errors and `.sr-only` for labels that are read aloud but not shown.

## Layout

- Desktop: a 236px sidebar (brand mark, org name, nav, and the signed-in person
  with sign-out at the bottom), then the page. Pages top out at 1240px wide.
- Phones (under 900px): a top bar with a menu button. The sidebar slides in over
  a dimmed page, and its shadow shows only while it is open.
- Page header: a 30px Sora title, one line saying what the page is for, and the
  primary action on the right. No eyebrow labels above titles.
- Candidates is a list and a detail, like Mail. How the resume matches the job
  is labelled as a guide. It never hides or ranks out a candidate.

## Connect an AI assistant

- `/profile/ai`, reachable by everyone on the team: a Settings card for HR managers, a
  link on My Profile for managers and interviewers. Both show only when the paid
  feature is on for the account (`GET /api/account/api-keys` says `included`).
- The page wraps the shared `airral-ai-connect` component (`libs/shared-ui`), also used
  by the applicant portal. It shows a new key once, from memory only, and never puts it
  in the address, title, storage or console.

## Words

- Sentence case everywhere, with verbs for actions: "New job", "Schedule
  interview", "Save draft", "Send to candidate".
- Status pills read as words ("Scheduled", "Open"), never raw enum values in
  capitals.

## Verify

- Check each changed page at desktop width and at 375px, with no horizontal
  scroll.
- Run `nx build hr-portal` and `nx lint hr-portal`. Component styles warn at 8kb
  and fail at 15kb. Candidates is the largest, so move shared rules into
  `styles.css` instead of copying them.
