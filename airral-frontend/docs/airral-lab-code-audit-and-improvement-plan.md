# AIRRAL Lab Code Audit And Improvement Plan

Date: 2026-05-15

Scope: deep code/product audit of the current AIRRAL repo with the new lab-report-first direction in mind. This reviews frontend UX/UI, backend/API shape, flow readiness, mock systems, catalog readiness, and report-builder readiness.

Related docs:

- `airral-frontend/docs/pathology-lab-software-competitive-research.md`
- `airral-frontend/docs/airral-lab-competitor-gap-analysis.md`
- `airral-frontend/docs/airral-lab-coverage-plan.md`

## Current Reality

The codebase is still an ATS / applicant / HR product. There is no real lab-report product code yet.

Current frontend apps:

- `website`
- `admin-portal`
- `applicant-portal`
- `hr-portal`

Current backend domains:

- organizations
- departments
- users
- jobs
- applications
- interviews
- offers
- referrals
- candidate profiles
- feed/community posts
- activity/audit foundations

Missing lab domains:

- patient accounts
- family members
- yearly subscriptions
- payment intents/events
- lab orders/accessions
- sample/specimen workflow
- lab test catalog
- test parameters
- reference ranges
- formula rules
- result entry
- report templates
- report signatures
- report revisions
- report delivery events
- doctor folders

## Validation Results

Commands run:

- `yarn ngc -p apps/applicant-portal/tsconfig.app.json --noEmit`
- `yarn nx lint applicant-portal`
- `yarn ngc -p apps/hr-portal/tsconfig.app.json --noEmit`
- `yarn ngc -p apps/website/tsconfig.app.json --noEmit`
- `yarn ngc -p apps/admin-portal/tsconfig.app.json --noEmit`
- `./gradlew test`

Results:

- Applicant portal Angular compilation fails.
- Applicant portal lint passes with 7 warnings.
- HR portal, website, and admin portal Angular compilation pass.
- Backend Gradle build/test passes, but there are no backend tests, so this only proves compilation.

Blocking compile issue:

- `apps/applicant-portal/src/app/pages/candidate-dashboard/candidate-dashboard.component.ts:57` uses `User` without importing it.

## High-Priority Current Code Issues

### 1. Applicant Portal Does Not Compile

File: `airral-frontend/apps/applicant-portal/src/app/pages/candidate-dashboard/candidate-dashboard.component.ts`

Issue:

- Line 57 uses `loadCandidateData(user: User)` but `User` is not imported.

Impact:

- The applicant portal cannot compile.
- This blocks previewing the current candidate/dashboard work.

Fix:

- Import `User` from `@airral/shared-types`, or type the parameter as the return type of `AuthService.getCurrentUser()`.

### 2. Rich Candidate Workspace Components Are Built But Not Wired

Files:

- `components/daily-command-center`
- `components/workspace-feed`
- `components/hiring-radar`
- `components/job-rooms`
- `components/career-events`
- `components/workspace-metrics`
- `components/engagement-dock`
- `components/engagement-onboarding`
- `data/candidate-dashboard.mock-data.ts`

Issue:

- These richer components and mock data exist, but the active dashboard imports only `CandidateFeedComponent`.
- `candidate-dashboard.component.ts` currently uses `DashboardView = 'feed' | 'applications' | 'profile' | 'tracker'`.
- `models/candidate-dashboard.models.ts` defines a different `DashboardView = 'overview' | 'applications' | 'tracker' | 'profile'`.
- Extra CSS files such as `candidate-dashboard.theme.css` and `candidate-dashboard.workspace.css` are not referenced by the active component.

Impact:

- The repo contains two dashboard directions at once.
- UX changes may appear to exist in files but not render in the app.
- This is risky for the lab product because the same pattern would create dead lab components.

Fix:

- Choose one active dashboard route.
- Remove/deprecate unused mock workspace components or wire them deliberately.
- For lab work, start a dedicated `lab-portal` or dedicated lab module instead of layering lab mocks into the applicant dashboard.

### 3. Feed API Contract Does Not Match Frontend Type

Frontend expects:

- `FeedPageModel.items`
- `FeedPageModel.meta.page`
- `FeedPageModel.meta.totalPages`
- `CompanyFeedPostModel.companyId`
- `CompanyFeedPostModel.companyHeadline`
- `CompanyFeedPostModel.engagement.usefulCount`
- `CompanyFeedPostModel.engagement.responseCount`

Backend returns:

- `FeedPageResponse.items`
- `FeedPageResponse.page`
- `FeedPageResponse.totalPages`
- `FeedPostResponse.organizationId`
- `FeedPostResponse.companyName`
- `FeedPostResponse.usefulCount`
- `FeedPostResponse.commentCount`

Files:

- `airral-frontend/apps/applicant-portal/src/app/pages/candidate-dashboard/components/candidate-feed/candidate-feed.component.ts`
- `airral-frontend/libs/shared-types/src/lib/platform-models.types.ts`
- `airral-backend/src/main/java/com/airral/dto/response/FeedPageResponse.java`
- `airral-backend/src/main/java/com/airral/dto/response/FeedPostResponse.java`

Impact:

- Feed pagination will not work because `result.meta` does not exist.
- Post rendering can show missing data because `companyHeadline` and `engagement` are not returned in that shape.
- Reaction count updates can throw if `post.engagement` is undefined.

Fix:

- Either update backend DTOs to match the TypeScript models, or update frontend models/mappers to match backend responses.
- Add API contract tests for response shapes.

### 4. Candidate Application Mapping Uses The Wrong Date Field

File: `airral-frontend/libs/shared-api/src/lib/candidate-portal.service.ts`

Issue:

- Frontend maps `appliedAt: app.submittedAt`.
- Backend `ApplicationResponse` returns `appliedAt`, not `submittedAt`.

Impact:

- Candidate job history dates can render as invalid/empty.

Fix:

- Use `appliedAt: app.appliedAt`.
- Prefer typed API response models rather than `app: any`.

### 5. Database Types And Java Domain Types Drift

Files:

- `airral-backend/src/main/resources/db/migration/V1__init_schema.sql`
- `airral-backend/src/main/java/com/airral/domain/Job.java`
- `airral-backend/src/main/java/com/airral/domain/Application.java`

Issue:

- Database declares `ats_keywords TEXT[]`, `ats_matched_keywords TEXT[]`, `ats_missing_keywords TEXT[]`, and `ats_weights JSONB`.
- Java domain stores these as `String`.
- Services split/join them as comma-separated values.

Impact:

- This can break persistence or produce awkward conversion behavior.
- The lab product must not repeat this mistake with test parameters, reference ranges, formulas, and report templates.

Fix:

- Use one consistent structured approach.
- For lab catalog/report builder, use relational tables for parameters/ranges/formulas and JSON only for limited flexible metadata.

### 6. Error Handling Silently Drops Data Problems

File: `airral-backend/src/main/java/com/airral/service/CandidateProfileService.java`

Issue:

- JSON serialization/deserialization errors return `[]` instead of surfacing a real issue.

Impact:

- Data corruption or schema drift can hide quietly.
- For lab reports this would be unacceptable because reference ranges/formulas/results cannot fail silently.

Fix:

- Log structured errors and fail safely.
- For lab report data, never silently coerce clinical configuration to empty arrays.

### 7. Backend Request Validation Is Inconsistent

Files:

- `CreateFeedPostRequest`
- `FeedReactionRequest`
- `UpdateCandidateProfileRequest`
- controllers using these DTOs

Issue:

- Some request DTOs use `@Valid`; feed/profile request objects do not have meaningful validation annotations.

Impact:

- Invalid feed/reaction/profile data can be accepted.
- Lab endpoints will need strict validation from day one.

Fix:

- Add DTO validation and enum validation.
- For lab: validate age/sex/sample/test/result fields, formula definitions, reference ranges, and signature workflow.

## UX/UI Improvement Areas

### Current Applicant UI

Issues:

- Search input is read-only; it looks interactive but does not work.
- Main feed actions such as Save Role, Ask Recruiter, Follow Company are visible but not wired.
- Top navigation and dashboard direction are still applicant/social, not lab/product.
- There are multiple visual systems: old compact feed, newer workspace components, extra CSS theme files, and Material components.
- UI state is not driven by a single product flow.

Lab direction:

- Do not reuse applicant social UI for lab operations.
- Lab UI should be quiet, operational, and role-based.
- The first screen for lab tech should be a task surface, not a social feed.

Recommended lab UI shell:

- Left navigation: Reports, Pending Payment, Pending Results, Pending Signature, Doctor Folders, Catalog, Settings.
- Top search: patient/account/family search.
- Main flow panel: selected patient -> subscription state -> payment gate -> test selection -> result entry -> finalize.
- Right rail: patient/family summary, subscription status, doctor folder, recent reports.

## Backend Architecture Improvements For Lab

### Add A Dedicated Lab Domain

Do not place lab code under current candidate/application/job abstractions.

Recommended backend packages:

- `com.airral.lab.domain`
- `com.airral.lab.controller`
- `com.airral.lab.service`
- `com.airral.lab.repository`
- `com.airral.lab.dto`

First controllers:

- `PatientAccountController`
- `SubscriptionController`
- `PaymentIntentController`
- `LabOrderController`
- `TestCatalogController`
- `ResultEntryController`
- `ReportTemplateController`
- `LabReportController`
- `DoctorFolderController`

### Add Contract-First API Models

The current feed mismatch shows why we need contract discipline.

For lab:

- Create shared TypeScript models that match backend DTOs.
- Add mapper tests.
- Do not use `any` for API responses.
- Keep response shape stable before designing UI.

### Add Real Tests

Backend currently has no test sources.

Minimum lab tests:

- Subscription gate blocks inactive accounts.
- Payment webhook activates subscription only after verified event.
- Duplicate webhook is idempotent.
- Family member age/sex drives reference range selection.
- Formula calculates expected derived values.
- Abnormal/critical flags are applied.
- Signed report locks the used catalog/template version.
- Report revision creates amendment history.
- Doctor folder is created once and reused.

## Lab Flow Improvement Plan

The product flow is good, but should be implemented as state machines, not ad hoc booleans.

### Patient/Payment State

States:

- account found
- member selected
- subscription active
- pending payment
- payment succeeded
- payment failed
- payment expired

### Order State

States:

- draft
- blocked_payment
- ready_for_test_selection
- tests_selected
- sample_pending
- sample_received
- result_entry
- ready_for_review
- signed
- delivered
- amended

### Report State

States:

- draft
- values_entered
- calculated
- flagged
- review_requested
- signed
- locked
- delivered
- revised

Why:

- Lab staff need clear queues.
- Payment and report signing must be auditable.
- It prevents accidental report entry before payment.

## Catalog Improvement Plan

Current state:

- No lab catalog code exists.

Required first objects:

- `lab_departments`
- `lab_tests`
- `test_parameters`
- `reference_ranges`
- `formula_rules`
- `test_packages`
- `package_tests`
- `catalog_versions`
- `catalog_approvals`

Important catalog design choices:

- Never hardcode test fields in Angular components.
- Parameter metadata should drive result-entry forms.
- Reference ranges must support age, sex, unit, method, and effective dates.
- Formula rules need preview/test cases.
- Catalog edits need versioning and approval.
- Signed reports must preserve the exact catalog version used.

First seed catalog:

- CBC
- LFT
- KFT/RFT
- Lipid profile
- Thyroid profile
- HbA1c
- Blood sugar F/PP/R
- Urine routine
- HBsAg
- HCV
- HIV screen
- Dengue NS1/IgG/IgM
- Malaria antigen/card
- CRP
- Vitamin D
- Vitamin B12

## Report Builder Improvement Plan

Current state:

- No report builder code exists.

Required report-builder pieces:

- Structured result-entry form generated from catalog.
- Formula engine.
- Reference-range evaluator.
- Abnormal/critical flagging.
- Report preview renderer.
- Template sections.
- Signature workflow.
- Report lock/revision workflow.
- Print/download/send modal.
- Doctor folder attachment.
- Patient history attachment.

Report template must support:

- Lab letterhead
- Patient/account/family member details
- Accession/order number
- Sample details
- Registered/collected/received/reported timestamps
- Referrer doctor
- Result table
- Units
- Reference ranges
- Abnormal/critical markers
- Method and interpretation notes
- Technician/pathologist signature
- QR/secure access
- Page numbers

## Recommended Build Order

### Milestone 0: Clean Existing App Drift

1. Fix applicant compile error.
2. Align feed backend DTOs with frontend models or add a frontend mapper.
3. Fix candidate application date mapping.
4. Decide whether richer candidate workspace components are active or archive them.
5. Add basic API contract tests around feed and candidate portal.

### Milestone 1: Lab Foundation

1. Add `lab.types.ts` in shared types.
2. Add backend lab package/module boundaries.
3. Add migrations for patient accounts, family members, subscriptions, payment intents/events.
4. Add migrations for catalog departments/tests/parameters/ranges/formulas/packages.
5. Add migrations for lab orders, order tests, result entries, reports, signatures, delivery events, doctor folders.

### Milestone 2: Payment-Gated Report Flow

1. Search account/member.
2. Check subscription.
3. Create blocked draft order/payment session.
4. Generate payment link/dynamic QR through provider abstraction.
5. Verify webhook and activate subscription.
6. Unlock test selection.

### Milestone 3: Catalog-Driven Result Entry

1. Seed 15-20 common India tests.
2. Render result-entry fields from catalog metadata.
3. Add formulas for CBC/LFT/KFT-style derived values.
4. Add abnormal/critical flagging.

### Milestone 4: Report Preview And Finalize

1. Render report preview from structured data.
2. Add reviewer/pathologist sign-off.
3. Lock report after signing.
4. Auto-create/select doctor folder.
5. Open final modal: print, download, send patient, send doctor, copy secure link.

### Milestone 5: Polish And Scale

1. Add pending-payment queue.
2. Add pending-result queue.
3. Add pending-signature queue.
4. Add doctor folder search/dedupe.
5. Add patient history.
6. Add audit/revision views.

## Immediate Next Step

Before building lab UI, fix the current contract and compile problems. Then create the lab domain skeleton and catalog/result-entry MVP. The first valuable lab demo should be:

Search patient/family -> payment gate -> select CBC/LFT/KFT -> enter values -> calculate/flag -> preview report -> sign -> print/send modal.
