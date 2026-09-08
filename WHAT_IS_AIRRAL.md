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
- **Get ranked results** once your profile knows your target roles, skills and
  location.

> **Worth knowing.** Coverage isn't even. Postings from Greenhouse, Lever and Ashby
> almost always show pay and required experience. Workday and SmartRecruiters don't
> publish descriptions in a way the sync can read yet, so those postings arrive
> thinner — and they're a large slice of the total.

---

## Side two — if you're the one hiring

**[app.airral.com](https://app.airral.com)** is the employer's workspace. Write up a
role, publish it, and it appears on the public board alongside everything else. From
there you work the pipeline.

| What you can do | State |
| --- | --- |
| Write and publish a job, then close or reopen it | works |
| See every applicant, with counters per role | works |
| Move someone through review, shortlist, interview, offer, hired or rejected | works |
| Book interviews on a calendar and record feedback with a rating | works |
| Keep notes and a shared timeline on each candidate | works |
| Draft an offer | works |
| Send that offer to the candidate | **broken** |
| Hiring analytics | partial |
| Interview scorecards, hiring stages, interview kits, integrations | not wired up |

The core loop is real — everything marked *works* writes to a database and is still
there when you reload. The settings screens look finished but mostly aren't connected
to anything yet.

> **Before you try this side.** You can't sign yourself up. There's no working
> registration on any of the portals, and Google sign-in takes you through the account
> picker and then fails, so an account has to be created for you. And nothing in the
> product yet creates an application — so a freshly published job shows an empty
> Candidates page until someone adds one directly. If you want to look around this
> side, ask Harjit to set you up first.

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

That's the whole toolset. Both are read-only: nothing an assistant does here can change
anything in AIRRAL.

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

Ask Harjit. Keys are issued from the admin portal and there's no self-serve sign-up for
one. It'll look like `airral_ak_live_7X3VRCpG_…` and you only see it once, at the moment
it's created.

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

> **Treat the key like a password.** It isn't limited to job search. The same key
> authenticates against the whole AIRRAL API as whoever it was issued to, with their
> permissions. Don't paste it into a shared config, a repo, or anywhere you wouldn't
> paste a password.

### The limits, plainly

| | |
| --- | --- |
| Requests per minute | 60 |
| How far back the search looks | 60 days |
| Tools available | 2, both read-only |
| Can it apply to a job for you? | No |
| Can it see your saved jobs or profile? | No |

Search is keyword matching rather than anything cleverer, so it will sometimes hand back
something off-target. Ask Claude to filter the results and it will.

---

*Written from the code as it stands on 8 September 2026, not from a roadmap — everything
marked "works" was checked against the running product, and the things that don't work
yet are labelled rather than left out.*
