# AIRRAL Two-Sided Market Entry Study

Date: 2026-08-14

Scope: product-value review, applicant-to-employer marketplace strategy, code audit, competitive research, launch wedge, and a 90-day validation plan.

## Executive Decision

AIRRAL can hold meaningful value, but not as another broad job board, generic resume scanner, or full ATS.

The strongest product is a **trusted hiring loop**:

> AIRRAL helps applicants identify real roles worth their time, prove relevant experience truthfully, and request an introduction. Employers receive a small number of consented, constraint-aligned, evidence-backed candidates and commit to a clear response.

This solves two sides of the same failure:

- Applicants face stale jobs, weak relevance, repeated tailoring, opaque screening, and ghosting.
- Employers face excessive low-signal inbound volume, candidate ghosting, and expensive recruiting tools.

The product should reduce noise for both sides. It should not create more applications.

**Verdict:** proceed, but launch narrowly and operate the first matches manually. The applicant utility is a credible starting point; the two-sided connection is not launch-ready yet.

## Why There Is Market Value

The U.S. labor market remains large enough for a focused entrant. The Bureau of Labor Statistics reported 7.6 million job openings and 5.2 million hires in May 2026. Market size alone does not create an opening, but it confirms that AIRRAL does not need broad market share to build a useful business.

Source:

- https://www.bls.gov/news.release/jolts.nr0.htm

### Applicant pain is specific and measurable

iHire's 2025 study of 1,421 job seekers and 529 employers found:

- 59.0% of job seekers cited employer ghosting.
- 39.3% cited fake, fraudulent, or ghost jobs.
- 60.5% wanted hiring-timeline transparency.
- 57.1% wanted salary ranges.
- 36.2% wanted must-have qualifications separated from nice-to-haves.

Source:

- https://www.ihire.com/resourcecenter/employer/pages/the-state-of-online-recruiting-2025

The earlier job-seeker study covers the broader evidence and AIRRAL product gaps:

- `airral-frontend/docs/job-seeker-needs-product-gap-study.md`

### Employer pain is the mirror image

The same iHire study found:

- 59.7% of employers received too many unqualified applicants through job boards.
- 50.7% experienced candidate ghosting.
- 50.3% cited job-board cost.
- 34.2% also experienced too few applicants.

SHRM reports that nearly seven in ten organizations still have difficulty filling full-time roles. This is not simply a supply shortage: quality, credential, and fit signals are difficult to assess.

Sources:

- https://www.ihire.com/resourcecenter/employer/pages/the-state-of-online-recruiting-2025
- https://www.shrm.org/topics-tools/research/2025-talent-trends/recruiting

The shared opportunity is not "more candidates." It is **less low-signal work per credible interview**.

### Trusted introductions have stronger funnel evidence

Ashby's 2026 recruiting operations analysis found 52% of referred candidates passed initial screens, compared with 35% overall, and that referred candidates retained stronger conversion through later stages. Its broader Talent Trends dataset includes tens of millions of applications.

This does not mean AIRRAL should impersonate personal referrals. It supports a narrower inference: employers value candidates who arrive with stronger context, intent, and evidence than anonymous inbound applications provide.

Sources:

- https://www.ashbyhq.com/talent-trends-report/reports/recruiting-operations-benchmarks-talent-trends
- https://www.ashbyhq.com/talent-trends-report

## Competitive Reality

AIRRAL should assume that basic applicant tools are commodities.

- Teal offers resume versions, job tracking, keyword matching, and AI writing, with a free tier and paid plans from $13 per week or $29 per month.
- Simplify offers job matching, application autofill, tracking, and basic resume tools for free, plus resume tailoring.
- Wellfound already combines a startup job marketplace, free job posts, sourcing, an ATS, and done-for-you recruiting. Its published employer pricing ranges from free postings to $199 per sourcing seat per month, while Autopilot costs $500 per open role per month plus a 10% placement fee.

Sources:

- https://www.tealhq.com/pricing
- https://simplify.jobs/copilot
- https://help.simplify.jobs/articles/0515607-auto-tailoring-your-resume-with-copilot
- https://wellfound.com/recruit/pricing

Therefore AIRRAL will not win with:

- A resume score alone.
- A tracker alone.
- Application autofill alone.
- More scraped jobs alone.
- A searchable resume database alone.
- An AI label applied to conventional keyword filtering.

AIRRAL can differentiate by owning the decision and trust layer across both sides: job legitimacy, explicit constraints, candidate evidence, mutual consent, and response accountability.

## Current AIRRAL Assessment

### What already creates value

Applicant side:

- 5,200 active cached jobs in the local database snapshot.
- 129 active sources representing 127 source companies.
- Official ATS and career-page sourcing across multiple connector types.
- Source, freshness, salary, work mode, seniority, quality, visa, and fit signals.
- Resume parsing, resume health, job-specific gap analysis, saved jobs, and tracker foundations.
- Job-first applicant UI with list/detail scanning.

Employer side:

- Organization-scoped requisitions and applications.
- Applicant review, hiring stages, interviews, offers, scorecards, referrals, analytics, and role permissions.
- Requirements and nice-to-have fields already exist in the requisition form.
- The hire tool already expresses a next-action workflow rather than a passive database.

This is a stronger foundation than an idea-stage marketplace. AIRRAL has useful components on both sides.

### Launch blockers

#### 1. The two job systems are not connected

Applicant discovery is built around `ExternalJobPostingStore`. Employer-created requisitions use the separate `jobs` table and `/api/jobs` flow. `AIRRAL_INTERNAL` appears in the candidate source-type list, but the audit found no service that publishes internal requisitions into the applicant discovery store.

Result: AIRRAL currently has an applicant product and an employer product, not a functioning applicant-employer marketplace.

Required fix:

- Create one canonical public-job contract.
- Publish every eligible employer requisition into candidate discovery as `AIRRAL_INTERNAL`.
- Preserve one stable job ID across discovery, fit, save, introduction, application, and employer pipeline.
- Make internal AIRRAL roles visibly distinct because AIRRAL can provide response and timeline guarantees for them.

#### 2. Current employer ATS scoring is unsafe and low quality

`ApplicationService` calculates the score by checking configured keywords only against the cover letter. It then marks applicants below the threshold as not visible. The employer UI hides those applicants by default and sorts the remainder by that score.

This can suppress a qualified candidate who has a strong resume and a short or absent cover letter. It also creates a misleading appearance of AI precision.

Required fix before live hiring:

- Stop auto-hiding applicants based on this score.
- Replace one opaque percentage with structured evidence.
- Evaluate the parsed resume, application answers, and role requirements, not cover-letter substring presence.
- Separate hard, legally appropriate constraints from preferred qualifications.
- Show evidence and uncertainty for every inference.
- Keep the employer responsible for the decision.

Relevant code:

- `airral-backend/src/main/java/com/airral/service/ApplicationService.java`
- `airral-frontend/apps/hr-portal/src/app/pages/candidates/candidates.component.ts`

#### 3. Consent and identity release are missing

AIRRAL needs explicit candidate consent before sharing personal data with a company. A candidate should not become searchable merely because they uploaded a resume or used resume fit.

Required states:

- Private: visible only to the applicant.
- Open to introductions: discoverable only through de-identified matching.
- Invited: a company or AIRRAL proposes a specific role.
- Candidate accepted: identity and approved evidence are released to that company.
- Employer accepted or declined: candidate receives a clear result.
- Application active: both sides share a limited status timeline.

#### 4. Employer role intake lacks the trust contract

The current requisition form has title, description, free-text requirements, nice-to-haves, location, and salary. The marketplace needs structured fields for:

- Salary range and compensation type.
- Work mode and allowed locations.
- Work-authorization requirement and sponsorship willingness, with source and update date.
- Must-have versus preferred qualifications.
- Minimum credible experience, without inflated defaults.
- Interview stages and estimated total candidate time.
- Hiring timeline and target start date.
- Hiring contact or responsible team.
- Response commitment.

#### 5. Job freshness must become a product guarantee

AIRRAL already has source and freshness foundations, but complete source syncs should deactivate disappeared postings promptly. Internal AIRRAL jobs need even stronger rules: close them immediately when the employer closes the requisition, and notify saved or introduced candidates.

## Recommended Beachhead

Two-sided marketplaces have a cold-start problem because neither side receives full value without the other. Stripe's marketplace guidance recommends intentional sequencing and a narrow initial focus.

Source:

- https://stripe.com/resources/more/two-sided-marketplace-strategy

### Recommended atomic market

Start with:

> Work-authorization-aware candidates in two or three skilled role families, matched to 20-500 person U.S. employers that publish salary, work mode, authorization constraints, and a response timeline.

Candidate focus:

- Final-year students, recent graduates, and professionals with roughly 0-5 years of experience.
- Software/data and one non-technical role family selected from current job supply, such as finance/operations or business analytics.
- One metro cluster plus U.S. remote roles, chosen after measuring current source coverage.
- All work-authorization situations are welcome; AIRRAL provides clarity rather than promising sponsorship.

Employer focus:

- 20-500 employee companies where a founder, hiring manager, or small people team owns the decision.
- Employers with current openings and the ability to respond within five business days.
- A mix of employers with documented sponsorship history and employers that clearly hire candidates who already have work authorization.

Why this is a credible wedge:

- AIRRAL already models visa and job-source signals.
- Work authorization is a hard constraint that broad keyword matching handles poorly.
- The Institute of International Education reported 294,253 students on Optional Practical Training in 2024/25, up 21% year over year, within a total international student population of 1,177,766.
- USCIS publishes an H-1B Employer Data Hub, giving AIRRAL a public historical signal for employer petition activity.
- Universities, alumni groups, and international student associations provide concentrated candidate acquisition channels.

Sources:

- https://www.iie.org/news/open-doors-2025-press-release/
- https://www.uscis.gov/node/46817

Important guardrail: sponsorship history is evidence, not a promise. AIRRAL must show the source, reporting period, and confidence, and must never present immigration information as legal advice.

### Why not launch broad

A broad launch would compete simultaneously with LinkedIn and Indeed for jobs, Teal and Simplify for applicant workflow, Wellfound for startup talent, and mature ATS products for employers. AIRRAL would have low liquidity in every segment.

A narrow market lets AIRRAL manually ensure that:

- Candidates see enough relevant roles.
- Employers see enough credible candidates.
- Every introduction has a responsible human.
- Failures teach the product team something specific.

## The Product To Build

### Applicant flow

1. Upload a resume.
2. Review and correct the parsed profile.
3. Set hard constraints: roles, location, work mode, salary floor, work authorization, relocation, and avoid terms.
4. See verified, relevant jobs with an apply, review, or skip recommendation and evidence.
5. Run role-specific fit and make truthful edits.
6. Choose either external apply or `Request AIRRAL introduction` for participating employers.
7. Review exactly what the employer will receive.
8. Consent to that one introduction.
9. Track the employer response, application stage, next action, and follow-up.

### Employer flow

1. Create a transparent role brief with structured must-haves and nice-to-haves.
2. Commit to salary/work-mode/authorization/timeline fields and a response target.
3. Receive a small candidate slate, initially curated by AIRRAL.
4. Review an evidence card instead of an unexplained score.
5. Accept, request clarification, or decline with a short reason.
6. Move accepted candidates through a simple pipeline or sync to an existing ATS later.
7. Close the requisition and notify all active candidates.

### Candidate evidence card

The employer should see:

- Candidate-approved name and contact only after consent.
- Target role and location/work-mode alignment.
- Work-authorization answer exactly as provided by the candidate.
- Required qualifications: met, uncertain, or not evidenced.
- Relevant skills with resume evidence, not inferred keyword counts.
- Experience range with confidence and supporting roles.
- Salary alignment.
- Availability and interview scheduling preferences.
- Candidate's short reason for interest.
- AIRRAL's introduction rationale in plain language.

Do not show a single score as the primary decision. A score may summarize, but evidence must remain visible and the applicant must be able to correct the underlying profile.

## Trust Contract

Participating employers should agree to:

- Publish salary, work mode, authorization constraints, and must-have qualifications.
- Confirm the opening is active.
- Respond to an accepted introduction within five business days.
- Close the role promptly when hiring stops.
- Avoid using AIRRAL data outside the agreed hiring purpose.
- Give a simple decline category where legally and operationally appropriate.

AIRRAL should agree to:

- Never sell or expose an applicant resume without explicit permission.
- Never fabricate candidate skills or experience.
- Explain match evidence and uncertainty.
- Remove stale roles quickly.
- Allow profile correction, introduction withdrawal, and account deletion.
- Audit ranking and screening outcomes for systematic exclusion.

## Go-To-Market Plan

### Phase 1: 20 design-partner employers

Recruit employers manually. Do not buy broad applicant traffic yet.

Channels:

- Direct founder and hiring-manager outreach to companies already represented in AIRRAL's source data.
- Employers with active roles and public evidence of hiring international talent.
- Local startup groups, chambers, university employer networks, and professional associations.
- Warm introductions from recruiters and career coaches.

Offer:

- One transparent role published free.
- AIRRAL rewrites the role brief into structured must-have and preferred fields.
- Five to ten consented, ready-to-talk candidates, not hundreds of applications.
- No fee for the first pilot role in exchange for weekly product feedback and funnel data.

### Phase 2: 200 candidate pilot

Channels:

- Three university career centers or international student offices.
- Student and alumni associations.
- Career coaches serving recent graduates or international professionals.
- High-intent workshops: "Find sponsor-aware roles and build an evidence-backed application."
- Candidate referrals after a useful introduction, not after signup.

Offer:

- Free resume parse and correction.
- Free verified-job search and work-authorization clarity.
- Free role-fit analysis for participating roles.
- Candidate-controlled introductions with response commitments.

### Phase 3: concierge matching

For the first 50 introductions, AIRRAL staff should review both role requirements and candidate evidence manually. This is not failure to automate. It is how the team learns:

- Which constraints truly decide the match.
- Where resume parsing fails.
- Why candidates decline roles.
- Why employers decline candidates.
- Which facts create trust.
- What employers will pay for.

Only automate repeated decisions after observing them.

## 90-Day Delivery Plan

### Days 1-15: validate the wedge

- Interview 30 candidates from the proposed segment.
- Interview 15 hiring managers or recruiters at 20-500 person companies.
- Interview five university career-services professionals.
- Ask about the last real application or hire, not hypothetical feature preferences.
- Secure five employer design partners before building marketplace UI.

Exit criteria:

- At least five employers provide a live role and agree to the transparency/response contract.
- At least 50 candidates agree to create a corrected evidence profile and consider role-specific introductions.

### Days 16-35: connect the two systems

- Publish internal requisitions into candidate discovery.
- Create stable job and introduction identifiers.
- Add employer transparency fields.
- Add candidate introduction consent and identity-release states.
- Remove score-based applicant hiding.
- Build an auditable evidence card.

### Days 36-60: run concierge introductions

- Launch with five roles and 50-100 candidates.
- Manually curate and send no more than ten candidates per role.
- Require employer response within five business days.
- Record structured accept/decline reasons on both sides.
- Conduct weekly interviews with candidates and employers.

### Days 61-90: productize what worked

- Add employer shortlisting and candidate response notifications.
- Attach application status and next action to the applicant tracker.
- Add simple interview scheduling or calendar handoff.
- Add employer funnel analytics.
- Start charging only after repeated interview outcomes demonstrate value.

## Pilot Metrics

Do not optimize for signups, job count, or application count.

Marketplace health:

- Roles with at least three qualified, consented candidates within seven days.
- Candidates receiving at least one relevant participating role within 14 days.
- Introduction acceptance rate on each side.
- Employer response within five business days.
- Introduction-to-screen rate.
- Screen-to-interview and interview-to-offer rates.
- Median time from role publication to first screen.
- Candidate-reported trust and usefulness.

Initial validation targets, not permanent benchmarks:

- 80% of introductions receive an employer response within five business days.
- 30% or more of mutually accepted introductions reach a screen.
- 20% or more of participating candidates receive at least one mutually accepted introduction during the pilot.
- At least 50% of employers ask to run another role or agree to pay for continued use.

## Pricing Hypothesis

Applicant:

- Keep job discovery, profile correction, fit evidence, introductions, and tracking free.
- Test optional paid resume versions or interview preparation later, after outcomes are proven.
- Never charge applicants for employer access or imply that payment improves ranking.

Employer:

- Pilot: first role free.
- Early subscription test: $199-$499 per active role per month for transparent posting, curated introductions, and workflow.
- Alternative after strong placement evidence: a 5-10% success fee, below traditional agency economics.
- Do not build pricing around selling resume access.

The published Wellfound pricing provides a useful ceiling and comparison, but AIRRAL should interview buyers before choosing the final model.

## What Not To Build Yet

- A national marketplace across every industry.
- A public candidate directory.
- Mass apply or autonomous application submission.
- A new general-purpose ATS.
- Social feed, rooms, or messaging as acquisition bets.
- Black-box AI screening.
- Automated video interviewing.
- Paid applicant ranking.
- Complex employer integrations before the manual loop works.

## Immediate Build Order

1. Remove cover-letter keyword scoring as an applicant visibility gate.
2. Connect employer requisitions to applicant job discovery.
3. Add the role transparency contract.
4. Add private/open/invited/accepted candidate consent states.
5. Build evidence cards from the corrected resume profile and job requirements.
6. Add introduction accept/decline and employer response deadlines.
7. Connect accepted introductions to the applicant tracker and employer pipeline.
8. Run the first five roles manually and measure screens, not applications.

## Final Product Position

AIRRAL should not promise that AI will decide who deserves a job.

It should promise something more credible:

> Real roles. Clear constraints. Truthful candidate evidence. Mutual consent. A human response.

That is valuable to applicants because it reduces wasted effort and uncertainty. It is valuable to employers because it reduces low-signal volume. It is also narrow enough to test without needing LinkedIn-scale traffic.
