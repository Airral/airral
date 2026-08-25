# AIRRAL Product Growth Strategy

Last updated: 2026-06-12

## Current State

AIRRAL has a working MVP: jobs (split-view browser) → resume fit (rule-based) → tracker (kanban) → apply (external link). The core loop works but lacks:
- Re-engagement (zero ways to reach users after they close the tab)
- Intelligent matching (newest-first, ~50 keyword rules)
- Broad job coverage (~25 tech companies from 4 ATS platforms)
- Unique differentiation (nothing you can't replicate with LinkedIn + ChatGPT)

---

## Product Ideas & Priorities

### P0: Email Re-Engagement (Week 1-2)

The single biggest gap. Users sign up, use AIRRAL once, and never return.

**What to build:**

| Feature | Trigger | Email Content |
|---------|---------|---------------|
| New job match alert | New job matches saved preferences | "3 new roles match your profile — one at [Company] with salary posted" |
| Follow-up reminder | `next_step_due_at` has passed | "You applied to [Role] 7 days ago. Time to follow up?" |
| Weekly digest | Sunday night cron | "Your week: 4 new matches, 1 needs follow-up, resume score: 68%" |
| Resume nudge | Resume uploaded but no fit run after 2 days | "You uploaded your resume but haven't checked fit against any role yet" |
| Saved job change | A saved job was reposted or expired | "[Role] at [Company] was reposted — they may still be looking" |
| Stale tracker | User hasn't logged in for 7 days with active saved jobs | "You have 3 saved roles — want to review them?" |

**Tech:** Spring Boot Mail + Gmail SMTP (500 emails/day free) or Resend free tier (100/day). Spring `@Scheduled` triggers. Thymeleaf email templates. Unsubscribe link in every email.

**In-app:** Badge on Tracker tab showing pending actions. "What's new since your last visit" banner on Jobs page.

---

### P0: Instant Resume Health Score (Week 2)

Users upload a resume and get... nothing until they manually click "Run Fit" on a specific job. This kills the first-session wow.

**What to build:**

Immediately after upload, show:
- Overall score (0-100)
- Word count check (too short < 300 words, too long > 1200 words)
- Quantified achievements found (regex: numbers + % or $)
- Action verb density (check against 50-verb list: "led", "built", "increased", etc.)
- Contact info present (email, phone, LinkedIn)
- Skills density vs. target role average
- ATS format issues (tables, columns, headers detected in parsed text)
- Top 3 immediate fixes

**Where it lives:** Shows on onboarding Step 2 right after upload completes. Also on Profile page resume section. Also as the first thing on Jobs page before user picks a role ("Your resume scores 68 — fix 2 things to improve matches").

---

### P1: Visa/Sponsorship Data Pipeline (Week 3)

Unique differentiator. No competitor cleanly integrates this into job cards. Data is 100% free.

**Data sources:**
- DOL OFLC LCA Disclosure (quarterly CSV, ~800K records/year): employer name, job title, wage, worksite, case status
- USCIS H-1B Employer Data Hub: petition counts and approval rates by employer
- E-Verify public employer list

**What users see:**
- Job card badge: "H-1B Sponsor (47 in 2025)" or "No sponsorship history"
- Job detail: sponsor confidence score, LCA filing history, median wage filed, cap-exempt likelihood
- Filter: "Visa-friendly only" toggle on Jobs page
- Profile: work authorization status feeds into match ranking

**Backend:** Scheduled job downloads CSVs quarterly, normalizes employer names (fuzzy match to `external_companies`), upserts into `company_immigration_signals` table (already exists, empty).

---

### P1: Smarter Resume-to-Job Fit (Week 3-4)

Current algorithm matches against ~50 hardcoded tech terms. Make it actually read the job description.

**Improvements (all free, no LLM):**

1. **JD section parser** — detect "Requirements", "Qualifications", "What we're looking for" sections and extract bullet items as requirements
2. **Skill synonym map** — "JS" = "JavaScript" = "ES6" = "ECMAScript", "AWS" = "Amazon Web Services" = "cloud infrastructure" (~200 synonym groups)
3. **TF-IDF text similarity** — Apache Lucene (already in Java ecosystem) to score resume text vs JD text
4. **Bullet quality scoring** — check each resume bullet for: action verb, quantified result, specificity, relevance to JD
5. **Experience level matching** — "5+ years" in JD vs. parsed years from resume timeline
6. **Education matching** — degree requirements vs. parsed education

**Result:** Fit score becomes meaningful. "Missing requirements" are pulled from the actual JD, not a hardcoded list.

---

### P1: Broader Job Sources (Week 4-5)

Currently ~25 tech companies. Need thousands of jobs across industries.

| Source | Cost | Coverage | Effort |
|--------|------|----------|--------|
| More Greenhouse boards (find 100+ public board tokens) | $0 | More tech/startup | Low — just add tokens to config |
| More SmartRecruiters companies | $0 | Enterprise roles | Low — find company slugs |
| USAJOBS API | $0 | All US federal/government | Medium — new connector |
| Adzuna API (free tier: 250 calls/day) | $0 | Broad market, all industries | Medium — new connector |
| Jooble API (free for startups) | $0 | Aggregator, global coverage | Medium — new connector |
| Recruitee public boards | $0 | Mid-market companies | Low — similar to existing connectors |
| Workable public boards | $0 | SMB companies | Low — similar pattern |

**Target:** Go from ~2,000-5,000 jobs to 50,000+ covering healthcare, finance, government, retail, logistics, education, manufacturing.

---

### P1: Weighted Job Ranking (Week 4)

Replace "newest first" with personalized scoring.

```
rank_score =
    (skill_overlap × 35)        // resume skills ∩ JD requirements
  + (role_title_match × 20)     // target role matches job title
  + (location_match × 15)       // matches location preference
  + (salary_in_range × 10)      // posted salary fits expectation
  + (work_mode_match × 10)      // remote/hybrid/onsite preference
  + (freshness × 5)             // newer = slight boost
  + (source_quality × 5)        // official ATS > aggregator
  + (visa_fit × bonus)          // if user needs sponsorship, boost sponsors
```

**Where:** Backend `CandidateJobsController` → new ranking service. Frontend shows "Why this matches" with top 2-3 reasons.

---

### P2: Follow-Up Intelligence (Week 5-6)

Make the Tracker actually useful vs. a spreadsheet.

**Features:**
- Auto-set follow-up reminder when user marks "Applied" (default: 7 days)
- "Follow up now" card with suggested email template (rule-based, not AI)
- Interview prep section: extract likely topics from JD requirements ("Expect questions about: distributed systems, team leadership, system design")
- "Days since applied" counter on each tracker card
- Application velocity: "You applied to 3 roles this week — above average"
- Ghosting detector: "Applied 21 days ago with no response — consider moving on"

---

### P2: Blog & SEO Content (Week 5-6)

The `/blog` route exists but is empty. Free organic traffic source.

**10 launch articles (write yourself or use free AI to draft):**

1. "Top 50 companies that sponsor H-1B visas in 2026" — high-intent search traffic
2. "How to check if a company sponsors work visas before applying" — leads to AIRRAL's visa filter
3. "Resume keywords that pass ATS screening in 2026" — leads to resume fit tool
4. "How to follow up after a job application (with templates)" — leads to Tracker
5. "Remote jobs that sponsor H-1B: complete list" — very high search volume
6. "What salary to expect for [role] in [city] 2026" — leads to job browse
7. "How long to wait before following up on a job application" — leads to Tracker
8. "Signs a company is actually hiring vs. ghost jobs" — leads to job quality signals
9. "STEM OPT jobs: which companies are E-Verify enrolled" — leads to visa features
10. "How to tailor your resume for each job application" — leads to resume fit

**SEO value:** These are high-intent queries that job seekers Google every day. Each article ends with a CTA to use AIRRAL's specific feature.

---

### P3: In-App Notifications & Badges (Week 6)

- Red badge on Tracker: "2 follow-ups due"
- "New" badge on Jobs: "12 new matches since last visit"
- Banner on login: "What changed: 5 new roles, 1 saved job reposted"
- Progress summary: "This month: 8 roles reviewed, 3 applied, 1 interviewing"

---

## How to Scale to 1 Million Users (Product-Led, Not Infra)

### The Core Insight

Job seekers don't choose tools by reading marketing pages. They find tools that solve an *active pain* at the exact moment they're feeling it. You scale by being present at those moments.

---

### Growth Engine 1: SEO + Public Utility Pages

**How LinkedIn got millions of job seekers:** Every job posting is a public indexed page. Every company profile is a public page. Google sends traffic to LinkedIn because LinkedIn has the content people search for.

**AIRRAL equivalent:**

| Public Page | Search Query It Captures | Volume |
|-------------|-------------------------|--------|
| `/companies/{company}/visa-history` | "does [company] sponsor H-1B" | 50K-200K/mo |
| `/companies/{company}/salaries` | "[company] salary [role]" | High |
| `/jobs?visa=true&role=software-engineer` | "H-1B sponsor software engineer jobs" | 30K-100K/mo |
| `/blog/top-h1b-sponsors-2026` | "companies that sponsor H-1B" | 100K+/mo |
| `/tools/resume-score` | "free ATS resume checker" | 200K+/mo |
| `/tools/salary-lookup` | "software engineer salary [city]" | Very high |
| `/jobs?source=usajobs&category=healthcare` | "government healthcare jobs" | High |

**The play:** Make your visa data, resume scorer, and salary signals into **free public tools** that capture search traffic. Each tool ends with: "Create a free account to save this and track applications."

**Scale math:** If 1% of visitors convert to signup, you need 100M page views for 1M users. With 50 high-ranking pages getting 10K visits/month each = 500K/month. With 500 pages (job listings, company profiles, blog posts) = 5M/month. Timeline: 18-24 months to 1M at this rate. Accelerate with viral/referral loops below.

---

### Growth Engine 2: Free Resume Tool as Viral Entry Point

**How Grammarly, Canva, and Jobscan grew:** Give away a free utility that's good enough to share.

**AIRRAL equivalent:**

Create a **public resume health checker** at `/tools/resume-score`:
- No signup required
- Upload PDF → instant score + 5 issues
- "Sign up free to get detailed fixes, job matching, and visa sponsorship data"
- Shareable result: "My resume scored 72/100 on AIRRAL — check yours"

**Why this scales:**
- People share resume tips in Discord servers, Reddit, university WhatsApp groups, and Twitter
- Students share tools with classmates
- Career coaches and bootcamps recommend free tools to students
- One share → 5-20 new visitors (job seekers cluster in communities)

---

### Growth Engine 3: Community Seeding (Free Distribution)

**Where job seekers already hang out:**

| Channel | Strategy | Cost |
|---------|----------|------|
| Reddit r/cscareerquestions (900K members) | Answer questions, link to visa data or resume tool as genuinely helpful resource | $0 |
| Reddit r/h1b, r/immigration (100K+) | Share "companies that sponsored this year" with AIRRAL link | $0 |
| Blind (anonymous tech worker app) | Share salary/visa data | $0 |
| Discord (CS/bootcamp servers: 50+ with 10K+ members) | Share resume tool in #resources channels | $0 |
| Twitter/X job search community | Post weekly "companies hiring + sponsoring" threads with AIRRAL source | $0 |
| LinkedIn organic posts | Share job market insights from your data | $0 |
| University career centers | Email 50 university career offices offering free tool for their students | $0 |
| Bootcamp partnerships | Offer free resume tool to bootcamp graduates (Lambda, Hack Reactor, etc.) | $0 |
| International student offices | US universities have ~1M international students, all need visa sponsor data | $0 |
| H-1B attorney referrals | Immigration lawyers have clients who need jobs | $0 |
| Meetup/event presence | Attend job search meetups, demo the tool | $0 |

---

### Growth Engine 4: Word-of-Mouth Triggers

People share tools when:
- The tool gives them a surprising insight ("I didn't know my resume was missing X")
- The tool has data they can't find elsewhere ("This company sponsored 200 people last year!")
- The tool saves them significant time ("I used to track applications in a spreadsheet")
- They got a job and credit the tool

**Built-in share triggers:**

| Moment | Share Prompt |
|--------|-------------|
| Resume score calculated | "Share your score" button → Twitter/LinkedIn card |
| Found visa-friendly company | "Share this employer's H-1B history" → link to public company page |
| Got interview | "Tell friends about AIRRAL" prompt |
| Got offer | "I got hired! AIRRAL helped me" → shareable card |
| Weekly digest email | "Know someone job searching? Forward this" link in footer |
| Saved 5+ jobs | "Invite a friend to compare notes" |

---

### Growth Engine 5: Data Network Effect

The more users you have, the better the product gets for everyone:

| Users | Data Generated | Product Improvement |
|-------|---------------|---------------------|
| 1K | Application outcomes | "Average response time from this company: 12 days" |
| 10K | Resume fit patterns | "People who got hired had these skills on their resume" |
| 50K | Salary reports | "Real salary for this role at this company: $X" (self-reported) |
| 100K | Interview reports | "This company's interview has 4 rounds, takes 3 weeks" |
| 500K | Company response rates | "This company responds to 40% of applicants" |
| 1M | Market intelligence | "Hiring velocity: this company posted 50 roles this month vs. 10 last month" |

**Key insight:** You don't need users to actively "post" content. Just tracking their saved jobs, fit scores, and application statuses generates aggregate intelligence that makes the product better.

This is where AIRRAL beats ChatGPT: ChatGPT has no memory of what other users did. AIRRAL accumulates signal.

---

### Growth Engine 6: Employer-Side Flywheel

Your HR portal creates a two-sided marketplace effect:

```
More applicants → Employers want to post on AIRRAL → More jobs → More applicants
```

**Free employer tier (Quick Hire) is the wedge:**
- Small companies get a free ATS + job posting
- Their jobs appear on AIRRAL's applicant portal
- More real jobs = more applicant value
- Later: charge for premium features (analytics, priority placement)

---

## Customer Acquisition Channels (Ranked by Cost & Impact)

### Tier 1: Free, High Impact

| Channel | What To Do | Expected Impact |
|---------|-----------|-----------------|
| **SEO / public pages** | Visa company profiles, resume tool, salary pages, blog | 50K-500K visits/month in 12 months |
| **Reddit** | Genuinely helpful answers in r/cscareerquestions, r/h1b, r/immigration, r/jobs | 1K-5K signups/month |
| **University international student offices** | Cold email 50 offices: "Free visa sponsor lookup tool for your students" | 500-2K signups per university partnership |
| **Bootcamp partnerships** | Offer free premium for their graduates | 200-1K per bootcamp |
| **Twitter/X threads** | Weekly "who's hiring + sponsoring" data thread | 1K-10K impressions per thread |
| **Product Hunt launch** | Launch resume tool or visa lookup as a free product | 2K-10K signups on launch day |
| **Hacker News** | "Show HN: Free tool to check if a company sponsors H-1B" | 5K-50K visitors in one day |

### Tier 2: Free, Medium Impact

| Channel | What To Do | Expected Impact |
|---------|-----------|-----------------|
| **LinkedIn organic** | Post data insights: "Top 20 companies that increased H-1B sponsorship in 2026" | Brand awareness, slow signup trickle |
| **Discord servers** | Share resume tool in career/bootcamp Discords | 100-500 signups/month |
| **Quora answers** | Answer visa/resume questions with links to tools | Long-tail SEO, 50-200/month |
| **YouTube shorts/TikTok** | 60-second "did you know [company] sponsors H-1B?" clips | Awareness, hard to measure |
| **Email signatures** | Add "Check your resume score free" link to team emails | Slow but free |
| **Open source / GitHub** | Open source the resume parser or visa data pipeline → community contributes | Developer goodwill + backlinks |

### Tier 3: Cheap ($0-100/month)

| Channel | What To Do | Expected Impact |
|---------|-----------|-----------------|
| **Google Ads (visa keywords)** | Bid on "H-1B sponsor companies" — very targeted | $0.50-2/click, high intent |
| **Facebook/IG ads to international students** | Target .edu emails + "work visa" interest | $5-15 per signup |
| **Sponsor a newsletter** | Pay $50-100 to appear in a career/immigration newsletter | 200-1K clicks |

---

## The 1M User Product Roadmap

```
PHASE 1: Foundation (Month 1-2)
├── Email re-engagement (stop losing users after Day 1)
├── Resume health score (instant wow)
├── Visa data pipeline (unique value)
└── Target: 0 → 5K users (existing signups + Product Hunt)

PHASE 2: Distribution (Month 3-4)
├── Public resume tool at /tools/resume-score (no login required)
├── Public visa company pages at /companies/{slug}/visa
├── Blog: 10 SEO articles (H-1B, resume, salary)
├── Reddit + Discord seeding
├── University outreach (cold email 50 career offices)
└── Target: 5K → 25K users

PHASE 3: Retention (Month 5-6)
├── Smarter resume fit (JD parsing + synonyms)
├── Broader job sources (USAJOBS + Adzuna + 100 more boards)
├── Weighted ranking (personalized job feed)
├── Follow-up reminders + interview prep
├── Share triggers (resume score cards, visa data sharing)
└── Target: 25K → 100K users

PHASE 4: Network Effects (Month 7-12)
├── Aggregate company response signals ("responds in 5 days")
├── Anonymous salary data from users
├── Application outcome tracking → "people like you got hired at..."
├── Employer flywheel (free job posting tier attracts companies)
├── Referral program ("invite 3 friends, unlock interview prep")
├── Weekly "job market" email digest people forward to friends
└── Target: 100K → 500K users

PHASE 5: Moat (Month 12-18)
├── Enough data to power real ML recommendations
├── Company hiring velocity signals (only possible with scale)
├── Local LLM for resume rewrites (Ollama, still free)
├── Partnership with major bootcamps and universities
├── International expansion (UK, Canada, Australia visa data)
└── Target: 500K → 1M users
```

---

## Key Product Decisions for Scale

### What to keep free forever (drives growth):
- Public job browsing
- Resume health score (basic)
- Visa sponsor lookup (public pages)
- Job alerts (email)
- Save up to 10 jobs
- Basic resume fit

### What to gate behind signup (captures users):
- Full resume fit with detailed fixes
- Unlimited saved jobs
- Tracker with reminders
- Personalized ranking
- Interview prep
- Follow-up templates
- Weekly digest email

### What to charge for later (monetization):
- AI-powered resume rewrites (when LLM is affordable)
- Unlimited resume fit checks per day
- Priority job alerts (before free users)
- Salary negotiation data
- Company deep-dive reports
- Employer analytics (B2B revenue)

---

## The One Metric That Matters at Each Phase

| Phase | North Star Metric | Why |
|-------|------------------|-----|
| Phase 1 | Weekly active users returning | Proves re-engagement works |
| Phase 2 | Signups from organic/SEO traffic | Proves distribution works |
| Phase 3 | Resume fit checks per user per week | Proves core value loop |
| Phase 4 | Invite-driven signups (% of new users from referral) | Proves word-of-mouth |
| Phase 5 | Monthly revenue per user | Proves business sustainability |

---

## Summary

AIRRAL scales to 1M users by:
1. **Being useful enough that people come back** (email alerts, reminders, fresh jobs)
2. **Giving away free public tools** that capture Google search traffic (visa lookup, resume scorer)
3. **Being present in communities** where job seekers already talk (Reddit, Discord, universities)
4. **Building data network effects** where each user makes the product better for everyone
5. **Never paying for distribution** until organic channels are exhausted

The product moat is: AIRRAL accumulates application intelligence (who sponsors, who responds, what resumes win) that no single-session ChatGPT conversation can replicate.
