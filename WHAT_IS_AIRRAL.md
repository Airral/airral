# What AIRRAL Is

A job site with an opinion, an applicant tracker for the companies hiring through it,
and a way to let an AI assistant search the whole thing for you.

Most job boards hand you a list and leave you to it. AIRRAL pulls postings straight
out of the systems companies actually hire with — Greenhouse, Workday, Ashby, Lever,
SmartRecruiters — and then tries to tell you which ones are worth your time. Around
15,000 postings are live at any moment.

There are two sides to it, and a third way in that most products don't have.

---

## Side one — if you're looking for a job

**[apply.airral.com](https://apply.airral.com)** — you land straight on the job list,
no sign-up wall. Search, filter, read full descriptions and click through to apply,
all without an account.

What makes it different from a list of links is the panel beside each job, headed
*Is this job worth your time?* It splits into three: what's good about the role, what
to check before applying, and what to do next.

### What it tells you about a job

- **The pay, and where the number came from.** When a company published a salary
  range, you see it with an "Employer posted" marker. That distinction matters — it
  separates a figure the company stands behind from one nobody could find.
- **What the posting says about visa sponsorship**, quoted from the text rather than
  guessed at. If it says nothing, it says nothing.
- **Whether it's remote, hybrid or on-site** — and honestly admits when the employer
  never said, which is most of the time.
- **How much experience it asks for**, pulled out of the description.

### If you make an account

- **Upload your resume** (PDF or Word, 5 MB) and get it scored out of 100 with a
  letter grade — length, how much of it is measurable results, keyword coverage,
  formatting.
- **Run it against a specific job** to see which requirements you match and which
  you don't.
- **Save jobs to a tracker** with six columns, from Saved through to Rejected.
- **Apply to jobs companies post on AIRRAL** with the resume on your profile, and
  follow each application's stage on the tracker: applied, in review,
  interviewing, offer, hired or not moving forward.
- **Answer an offer** a company sends you, right on the tracker.
- **Get ranked results** once your profile knows your target roles, skills and
  location.

> **Worth knowing.** Coverage isn't even. Postings from Greenhouse, Lever and Ashby
> almost always show pay and required experience. Workday and SmartRecruiters don't
> publish descriptions in a way the sync can read yet, so those postings arrive
> thinner — and they're a large slice of the total.

---

## Side two — if you're the one hiring

**[app.airral.com](https://app.airral.com)** is the employer's workspace. Sign your
company up at [airral.com/sign-up](https://airral.com/sign-up). AIRRAL reviews every new
company, usually within a business day, and once it's verified your published jobs
appear on the public board alongside everything else. A checklist on the home page
walks you through the first steps.

| What you can do | State |
| --- | --- |
| Sign your company up and invite your team by email: HR managers, hiring managers and interviewers | works |
| Keep a company profile that shows on your jobs: logo, website, industry, size, a few lines about you | works |
| Write and publish a job with a department, a hiring manager and an interview kit; close, reopen, or mark it filled | works |
| Get applications inside AIRRAL, with the applicant's resume, or add a candidate by hand | works |
| Move someone through review, shortlist, interview, offer, hired or rejected, with notes and a shared timeline | works |
| Book interviews with teammates on them, shown in everyone's own time zone, with calendar invites | works |
| Interviewers score each interview against the job's kit; drafts stay private until submitted | works |
| Send an offer, open until the end of a day in your time zone, which the candidate accepts or declines on AIRRAL; their answer is what hires them | works |
| Close out a job after a hire: mark it filled and turn down the rest | works |
| Emails to candidates and the team: application received, interview booked, offers, decisions | once SMTP is set up in production |
| Hiring analytics | partial, and not in the menu yet |
| Custom hiring stages, integrations, plans and billing | not built yet |

Everything marked *works* writes to a database and is still there when you reload,
and one automated test runs the whole loop, from signup to hire, on every change.
Hiring managers see only the jobs they hire for, and interviewers only the interviews
they're on. Settings only shows screens that save.

> **Worth knowing.** Sign-in links already arrive by email, through Firebase. The
> other emails need the API's SMTP settings, which production doesn't have yet: until
> it does, nobody is emailed about interviews, offers or decisions, so tell candidates
> yourself.

---

## Side three — letting an AI search it for you

This is the part that doesn't exist on other job sites. AIRRAL runs an **MCP server** —
Model Context Protocol, the standard way to give an AI assistant access to a specific
tool. Point Claude at it once, and from then on you can just ask.

Instead of opening a tab, filtering, and reading thirty postings, you say *"find me
remote senior backend roles that pay over $200k"* and Claude searches AIRRAL, reads the
full descriptions of the promising ones, and tells you what it found. It can compare
roles, pull out the requirements you'd miss, and draft your application — all in the
same conversation, because it has the postings in front of it.

### What it can do

- `search_jobs` — search by keyword, optionally narrowed by location, work mode or company.
- `get_job` — read one posting in full, description and all.
- `list_company_jobs` — for HR managers and hiring managers: your company's own jobs and
  how hiring is going on each, in numbers (applied, new, in review, interviewing, offers,
  hired). Never an applicant's name or details. A manager sees the jobs they are hiring
  manager on. Interviewers' keys get the first two tools only.

All three are read-only: nothing an assistant does here can change anything in AIRRAL.

### What a search actually returns

```
3 postings matching "senior backend engineer":

— Lead Software Architect, Rust · Anduril
  Location: Broomfield, Colorado, United States
  Type: Full-time
  Pay: USD $219k-$290k
  Posted: Just updated
  Apply: https://boards.greenhouse.io/andurilindustries/...

— Senior Software Engineer, Identity · Twilio
  Location: Remote - US
  Work mode: REMOTE
  Type: Full-time
  Pay: Salary not listed
  Posted: Just updated
  Apply: https://job-boards.greenhouse.io/twilio/jobs/8056276
```

*A real response from mcp.airral.com, 8 September 2026.*

---

## How to use the MCP

### 1. Get a key

Connecting an AI assistant is a paid feature. Where it's on for your account, make your
own key: in the applicant portal, **Profile → Connect an AI assistant**; in the company
portal, **Settings → Connect an AI assistant** (or **My Profile** for managers and
interviewers). Until plans exist it's on only for the accounts listed in the
`AI_ACCESS_EMAILS` repository variable. An admin can still issue one from the admin
portal.

A key looks like `airral_ak_live_7X3VRCpG_…`, you see it only once, when it's made, and it
works for 90 days. You can have three at a time and revoke any of them from the same
page. A key made there works only while the feature is on for your account.

### 2. Add it to Claude Code

Put this in `~/.claude.json`, with your key in place of the placeholder:

```json
{
  "mcpServers": {
    "airral": {
      "type": "http",
      "url": "https://mcp.airral.com/mcp",
      "headers": {
        "Authorization": "Bearer airral_ak_live_…"
      }
    }
  }
}
```

### 3. Restart Claude Code and just ask

No commands to learn. Try any of these:

- *"Find me remote senior backend roles."*
- *"What's Anthropic hiring for in San Francisco?"*
- *"Search AIRRAL for product designer jobs that publish a salary, then tell me which pay best."*
- *"Read this posting in full and tell me if I'm a fit — here's my resume."*

> **Treat the key like a password.** It works only with the MCP endpoint -- the rest of
> the AIRRAL API refuses it -- but it is still yours. Don't paste it into a shared config,
> a repo, or anywhere you wouldn't paste a password. It stops working when you revoke it,
> reset your password, sign out everywhere, or your role or company changes.

### The limits, plainly

| | |
| --- | --- |
| Requests per minute | 60 |
| How far back the search looks | 60 days |
| Tools available | 2 for applicants and interviewers, 3 for HR managers and hiring managers, all read-only |
| Can it apply to a job for you? | No |
| Can it see your saved jobs or profile? | No |

Search is keyword matching rather than anything cleverer, so it will sometimes hand back
something off-target. Ask Claude to filter the results and it will.

---

*Written from the code as it stands on 8 September 2026, not from a roadmap — everything
marked "works" was checked against the running product, and the things that don't work
yet are labelled rather than left out.*
