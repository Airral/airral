# AIRRAL Lab Competitor Gap Analysis

Date: 2026-05-15

Scope: compare Labsmart LIS and Health Amaze against the AIRRAL app currently present in this repository. This focuses on pathology/lab workflow software and intentionally excludes machine/device connectivity.

Related research:

- `airral-frontend/docs/pathology-lab-software-competitive-research.md`
- Labsmart LIS: https://www.labsmartlis.com/
- Labsmart patient registration and billing: https://www.labsmartlis.com/features/patient_registration_and_billing
- Labsmart lab reporting: https://www.labsmartlis.com/features/lab_reporting
- Labsmart sample reports and report customization: https://www.labsmartlis.com/sample-reports
- Labsmart pathology report formats: https://www.labsmartlis.com/pathology-report-format
- Health Amaze: https://healthamaze.app/
- Health Amaze features: https://healthamaze.app/features
- Health Amaze lab report management: https://healthamaze.app/features/lab-report-management
- NABH/NABL diagnostic lab accreditation overview: https://www.nabh.org.in/diagnostic-labs/

## Executive Take

The AIRRAL codebase currently looks like a complete ATS / recruiting SaaS platform, not a pathology lab management product yet.

That is not bad news. AIRRAL already has reusable SaaS foundations: multi-tenant organizations, users, role-based access, feature tiers, analytics, activity logs, audit logs, profile workflows, and feed/community behavior. Those are valuable building blocks.

The missing part is the lab domain itself. Labsmart and Health Amaze are not just dashboards; they are daily operating systems for a lab. Their core loop is patient intake -> bill/order -> sample/test workflow -> result entry -> pathologist signature -> report delivery -> patient/doctor access -> owner analytics.

AIRRAL does not yet have those lab objects in the visible codebase.

## Evidence From Current AIRRAL Repo

Current app identity:

- Root `README.md` describes AIRRAL as a "Complete ATS Platform."
- Frontend apps are `website`, `admin-portal`, `applicant-portal`, and `hr-portal`.
- HR routes are hiring-oriented: dashboard, hire, jobs, offers, candidates, interviews, analytics, settings, team review, referrals, profile, benefits.
- Applicant portal routes point to a candidate dashboard.
- Backend controllers are hiring-oriented: auth, jobs, applications, interviews, offers, referrals, analytics, HR encounters, activity feed, candidate profile, feed, users, departments.

Current database objects include:

- `organizations`
- `departments`
- `users`
- `jobs`
- `applications`
- `application_notes`
- `application_activities`
- `referrals`
- `interviews`
- `offers`
- `audit_logs`
- `hr_encounters`
- `activity_feed`
- `candidate_profiles`
- `feed_posts`
- `feed_reactions`
- `feed_comments`
- `company_follows`

Not found in the current schema/controllers:

- patients
- lab orders
- samples/specimens
- accession numbers
- lab test catalog
- test parameters
- reference ranges
- formulas
- abnormal flags
- report templates
- result entry
- pathologist/technician signatures
- lab report PDFs
- report QR codes
- invoices/payments tied to lab orders
- WhatsApp/SMS/email delivery events
- patient report portal
- doctor/referral business workspace
- collection center or branch workflows

## Capability Comparison

| Capability | Labsmart / Health Amaze | AIRRAL Current Repo | Gap / Translation |
| --- | --- | --- | --- |
| Multi-tenant organization | Yes, lab/account based | Yes, strong organization model | Reuse and rename for lab businesses. |
| Staff users and permissions | Yes | Yes, strong role/tier system | Need lab roles: owner, front desk, technician, pathologist, collection agent, doctor/referrer, patient. |
| Patient registration | Yes | No | Add patient intake, demographics, phone search, visit history. |
| Billing / invoice | Yes | Org billing email only; no operational billing | Add invoices/payments linked to lab orders. |
| Barcode / QR bill access | Yes | No | Add accession/order codes and QR report/bill access. |
| Yearly subscription gate | Not the main competitor framing | No | Add active subscription check before report creation. |
| Remote electronic payment | Competitors emphasize digital flows, but not this exact gate | No | Add payment link, dynamic UPI QR, or lab-account payment before report creation. |
| Test catalog | Yes | No | Add lab tests, departments, parameters, units, reference ranges, packages. |
| Sample workflow | Yes or implied | No | Add collected, received, processing, reported, signed, delivered statuses. |
| Report entry | Yes | No | Add result-entry workspace by order/test/parameter. |
| Formulas | Yes | No | Add formula rules for calculated parameters. |
| Abnormal flags | Yes | No | Add reference-range evaluation and critical flags. |
| Digital signatures | Yes | No | Add technician/pathologist signature profiles and sign-off workflow. |
| PDF customization | Yes | No | Add report templates, letterhead, sections, print/download preview. |
| WhatsApp/SMS/email delivery | Yes | No | Add delivery queue, channel preferences, delivery status, resend history. |
| Patient online access | Yes | Candidate portal exists but not patient health records | Add patient report portal and longitudinal patient history. |
| Doctor/referral business | Yes | Recruiting referral feature only | Add referrer doctors, commission/business reports, referral source analytics. |
| Collection center / branch | Health Amaze mentions collection center/branch support | AIRRAL has org/departments, no branch lab workflow | Add branches/collection centers and sample transfer states. |
| Audit trail | Yes | Yes, audit/activity foundations exist | Extend audit to bills, patient edits, test edits, report signing, delivery. |
| Analytics | Yes | Yes, hiring analytics exists | Rebuild around revenue, tests, TAT, pending reports, dues, referrals, delivery. |
| Engagement/feed | Not central to LIS, but communication matters | AIRRAL has feed/community ideas | Reuse carefully for lab announcements, doctor updates, patient communication, not as noisy social UI. |

## What AIRRAL Already Has That We Should Reuse

1. **Organization and tenancy**

   AIRRAL already isolates data by organization. For a lab product, this can become lab account / diagnostic center / chain ownership.

2. **Users, roles, and feature tiers**

   The permission model is a strong base. The roles need to become lab-specific, but the access-control pattern is already useful.

3. **Audit and activity foundation**

   The existing audit/activity approach maps well to lab compliance. The lab version should record patient edits, test changes, bill discounts, report revisions, signature events, and delivery events.

4. **Analytics shell**

   Hiring analytics can be replaced with lab operating analytics: daily revenue, pending samples, pending reports, turnaround time, due payments, doctor/referral contribution, test volume, and delivery success.

5. **Profile and portal architecture**

   Applicant profile and candidate portal patterns can inform a patient portal, but the domain model must be new. A patient portal should focus on report access, visit history, communication preferences, and verified report downloads.

6. **Feed/community pieces**

   AIRRAL's feed can help engagement, but for lab software the engagement should be operational: unread report-ready alerts, doctor requests, patient questions, abnormal result follow-up, and branch handoffs. It should not become a social feed unless there is a clear lab/customer reason.

## Focused Gap: Report Creation and India Test Catalog

This is the biggest missing feature area compared with Labsmart and Health Amaze.

Competitors do not ask labs to design every report from scratch. Their value is that a small Indian pathology lab can start with pre-built report formats and common tests, then edit them.

### What Competitors Have

Labsmart says its reporting workflow is:

- Test details and normal values are already set.
- Technician mainly enters the test result values.
- Normal values and interpretations can be edited from the report page.
- Reports can be printed or sent by WhatsApp, email, or SMS.
- Reports include registration number/barcode and TAT fields: registered, collected, received/processed, reported.
- Ready test database with common formats, units, age/gender normal values, custom tests, and panels.
- Auto-calculations for CBC, lipid profile, LFT, and KFT.
- Report states: new, in-progress, final, signed off, printed, pending payment.
- Report customization: letterhead, signatures, comments, interpretations, TAT visibility, QR code visibility, page numbers, patient address, method information, abnormal-result highlighting, test order.

Health Amaze says its reporting workflow is:

- 250+ pre-built diagnostic tests.
- Parameter definitions, units, reference ranges, and formulas.
- Smart formula engine for ratios, percentages, and other derived values.
- Automatic abnormal/high/low flagging based on reference ranges, age, and gender.
- Digital signatures for pathologist and technician.
- Custom test packages and profiles.
- Reusable interpretation notes and methodology references.
- Report generation connected to patient registration, billing, online access, and WhatsApp delivery.

### What AIRRAL Has Today

AIRRAL currently has no lab report builder, no test catalog, and no result-entry model.

Existing candidate/applicant profile and feed work cannot cover this. The lab product needs a dedicated report domain.

### Minimum Report Builder AIRRAL Needs

Objects:

- `patient_accounts`
- `patients`
- `family_members`
- `subscriptions`
- `payment_intents`
- `payment_events`
- `lab_orders`
- `order_tests`
- `lab_tests`
- `test_parameters`
- `reference_ranges`
- `formula_rules`
- `result_entries`
- `report_templates`
- `report_template_sections`
- `report_signatures`
- `report_revisions`
- `report_delivery_events`
- `doctor_folders`

Keep billing separate from the first report-builder milestone. The report flow needs patient/account eligibility, yearly subscription status, electronic payment verification, patient/order context, and accession numbers, but it does not need a full invoice/accounting system on day one.

Required workflow:

- Lab tech searches the patient/account by phone, name, or patient ID.
- Lab tech selects the account holder or family member who needs the test.
- System checks yearly subscription status.
- If inactive, system blocks report creation until electronic payment is verified.
- Payment can be remote payment link, dynamic UPI QR, or lab-paid from account.
- After payment success, lab tech creates order from selected patient/family member and selected tests/packages.
- Technician opens a result-entry screen grouped by department/test.
- System shows preloaded parameters, units, and reference ranges.
- Technician enters values only.
- Formula fields auto-calculate.
- Values auto-flag low/high/critical based on age, gender, method, and configured ranges.
- Pathologist reviews and signs.
- Report locks after signing, with explicit revision flow.
- Report PDF renders with lab letterhead, patient/order details, TAT, referrer doctor, result table, notes, method, signatures, QR code, and page numbers.
- System checks/creates the doctor folder and automatically adds the finalized report.
- Finalized report opens a modal for print, download, send to patient, send to doctor, or copy secure link.
- Report is stored in patient history and doctor folder.

### India Starter Catalog

We should not claim AIRRAL has "all tests people do in India" yet. It currently has none.

The first catalog should cover the common departments and panels competitors advertise publicly:

- Haematology: CBC, hemoglobin, TLC, DLC, platelet count, ESR, PT/INR, APTT, reticulocyte count, malaria card, filarial card.
- Biochemistry: blood sugar fasting/PP/random, HbA1c, serum creatinine, urea, uric acid, bilirubin, SGOT/AST, SGPT/ALT, alkaline phosphatase, proteins, albumin, calcium, sodium, potassium, chloride, cholesterol, triglycerides, HDL, LDL, VLDL, iron, TIBC, ferritin, vitamin B12, vitamin D.
- Panels/packages: CBC, LFT, KFT, lipid profile, thyroid profile/TFT, iron studies, viral marker, pregnancy profile, full body checkup.
- Serology and immunology: CRP, ASO, ANA, HBsAg, HBeAg, HCV, dengue NS1, chikungunya, beta HCG, anti-TPO, IgE.
- Clinical pathology: urine routine, urine albumin/creatinine ratio, semen examination.
- Microbiology: urine culture and sensitivity, blood culture, stool culture, sputum culture, pus culture, Gram stain, AFB.
- Endocrinology and hormones: T3, T4, TSH, FT3, FT4, insulin, cortisol, calcitonin where relevant.
- Histopathology/cytology support should be modeled differently from numeric blood tests because those reports are narrative/specimen-based.

Important: the software can ship starter templates, but reference ranges, formulas, methods, units, interpretations, and sign-off rules must be editable and reviewed by qualified lab/pathology staff. For India/NABL-minded labs, test scope, methods, ranges, and reporting details need to match the lab's actual validated process, not only a generic software template.

## Core Lab Modules AIRRAL Needs

### 1. Lab Daily Command Center

This should be the default screen, not a marketing dashboard.

Key sections:

- New registrations today
- Samples pending collection
- Samples received but not reported
- Reports pending pathologist signature
- Payments due
- Reports ready but not delivered
- Critical/abnormal results needing attention

### 2. Patient Intake and Billing

Billing can be a later module. For the report-first product, this section should be reduced to patient/family intake, subscription payment gate, and report order context.

Needed objects:

- `patient_accounts`
- `patients`
- `family_members`
- `patient_contacts`
- `subscriptions`
- `payment_intents`
- `payment_events`
- `lab_orders`
- `order_tests`
- `invoices` later
- `payments` later
- `discount_events` later

Main workflow:

- Search patient by phone/name
- Register patient or reuse existing patient
- Select account holder or family member
- Check yearly subscription status
- If inactive, collect electronic payment through remote link, dynamic UPI QR, or lab-paid account
- Select tests/packages
- Capture doctor/referrer and collection location
- Generate order/accession number
- Attach QR code to report access

### 3. Test Catalog and Packages

Needed objects:

- `lab_departments`
- `lab_tests`
- `test_parameters`
- `reference_ranges`
- `test_packages`
- `package_tests`
- `formula_rules`

Main workflow:

- Maintain 250+ common tests over time
- Define units, reference ranges, age/gender rules
- Build health packages
- Control report order/sections

### 4. Sample and Case Workflow

Needed objects:

- `samples`
- `sample_events`
- `collection_centers`
- `branch_transfers`

Main workflow:

- Registered
- Collected
- Received
- In processing
- Result entered
- Report generated
- Signed
- Delivered

This is where AIRRAL can beat competitors with a clean case timeline.

### 5. Report Workspace

Needed objects:

- `result_entries`
- `lab_reports`
- `report_templates`
- `report_sections`
- `report_signatures`
- `report_revisions`

Main workflow:

- Enter results by parameter
- Auto-calculate formulas
- Auto-flag abnormal/critical values
- Add interpretation/method/clinical notes
- Preview PDF
- Pathologist signs
- Lock or revision-control signed reports

### 6. Delivery and Patient Access

Needed objects:

- `delivery_events`
- `notification_preferences`
- `patient_portal_sessions`
- `report_access_tokens`

Main workflow:

- Send report by WhatsApp/SMS/email
- Track delivery state
- Allow QR download
- Let patient see past reports
- Keep a conversation/history trail around each report

### 7. Doctor / Referral Workspace

Needed objects:

- `referring_doctors`
- `doctor_accounts`
- `doctor_commissions`
- `referral_reports`

Main workflow:

- Track doctor/referral source per order
- Give doctors controlled report access
- Show referral revenue/test volume
- Support commission/settlement reporting if needed

## Recommended AIRRAL Lab App Structure

Frontend apps:

- `lab-portal`: daily lab operations for owner/front desk/technician/pathologist
- `patient-portal`: report access and patient history
- `doctor-portal`: referrer report access and referral activity
- `admin-portal`: platform-level account management

Backend modules/controllers:

- `PatientController`
- `LabOrderController`
- `BillingController`
- `SampleController`
- `TestCatalogController`
- `ResultEntryController`
- `LabReportController`
- `ReportDeliveryController`
- `DoctorReferralController`
- `CollectionCenterController`
- `LabAnalyticsController`

Shared types/libs:

- `libs/shared-types/src/lib/lab.types.ts`
- `libs/shared-api/src/lib/lab-api.service.ts`
- `libs/shared-ui/src/lib/lab-*` if shared UI components emerge

## UX Direction Compared With Competitors

Labsmart and Health Amaze appear feature-rich, but their competitive category can easily become crowded and form-heavy. AIRRAL should avoid copying that density.

The best UX angle for AIRRAL Lab:

- One command center per role.
- Clear status boards instead of one mega-dashboard.
- A case timeline for every patient order.
- Separate workspaces for Intake, Samples, Reports, Delivery, Doctors, Analytics, Settings.
- Use color by workflow state, not decoration.
- Keep daily actions visible: register patient, collect sample, enter result, sign report, deliver report.
- Make patient and doctor communication feel built-in, not bolted on.

## Priority Build Sequence

### Phase 1: Lab Operating Core

- Patients
- Test catalog
- Test packages
- Lab orders
- Billing/payment tracking
- Sample status board
- Result entry
- Basic report PDF preview

### Phase 2: Trust and Delivery

- Digital signatures
- Report locking/revisions
- QR-coded reports
- WhatsApp/SMS/email delivery events
- Patient report portal
- Audit history for patient, bill, test, and report changes

### Phase 3: Owner and Growth

- Revenue analytics
- Test volume analytics
- Turnaround-time dashboard
- Doctor/referral workspace
- Collection center/branch support
- Due-payment reports
- Delivery success reports

### Phase 4: Differentiators

- Guided lab setup by business type
- Cleaner report-template builder
- Abnormal/critical result follow-up queue
- Patient conversation timeline
- Smart operational alerts
- Optional AI assistance for report notes, interpretation drafts, and patient-friendly summaries with strict clinical review

## Product Conclusion

AIRRAL currently has the SaaS skeleton but not the pathology lab body.

The right move is not to force lab features into the existing applicant/HR pages. Build a dedicated lab domain with its own routes, components, data models, and role-specific command center. Reuse the platform pieces that are already strong: tenant isolation, auth, permissions, audit/activity, analytics shell, and portal structure.

The product opportunity is to become cleaner and more day-to-day useful than Labsmart and Health Amaze: less clutter, better workflow separation, stronger case timeline, better delivery history, and a calmer UI that helps lab staff finish work faster.
