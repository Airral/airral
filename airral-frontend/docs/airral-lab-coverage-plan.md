# AIRRAL Lab Coverage Plan

Date: 2026-05-15

Goal: make AIRRAL Lab cover the practical daily needs of Indian pathology/diagnostic labs in a way that is safer, cleaner, and more maintainable than simply hardcoding a huge list of tests.

Related docs:

- `airral-frontend/docs/pathology-lab-software-competitive-research.md`
- `airral-frontend/docs/airral-lab-competitor-gap-analysis.md`

Sources:

- Labsmart reporting: https://www.labsmartlis.com/features/lab_reporting
- Labsmart sample reports: https://www.labsmartlis.com/sample-reports
- Labsmart pathology report formats: https://www.labsmartlis.com/pathology-report-format
- Health Amaze report management: https://healthamaze.app/features/lab-report-management
- NABL accreditation process and scope: https://nabl-india.org/nabl-accreditation-process-scope/
- NABL accreditation documents: https://nabl-india.org/nabl/index.php?+m=index&c=publicaccredationdoc&docType=both
- NPCI UPI overview: https://www.npci.org.in/what-we-do/upi/product-overview/
- NPCI UPI AutoPay: https://www.npci.org.in/what-we-do/autopay/product-overview/
- Razorpay Payment Links: https://razorpay.com/docs/api/payments/payment-links/?preferred-country=IN
- Razorpay UPI QR webhooks: https://razorpay.com/docs/payments/payment-methods/upi-qr/webhooks/?preferred-country=IN
- Cashfree webhooks: https://www.cashfree.com/docs/payments/online/webhooks/overview

## Product Principle

Do not build a generic "report PDF maker."

Build a lab operating workflow where the report is the output of validated patient, order, sample, test, result, review, signature, and delivery data.

Competitors cover reporting because they combine:

- Pre-built test catalog
- Result-entry forms
- Formulas
- Reference ranges
- Abnormal flags
- Report templates
- Signatures
- WhatsApp/SMS/email delivery
- QR/patient access

AIRRAL needs to cover all of that as one system.

## Product Focus Decision: Report First, Billing Later

Billing can be a separate module.

For the first lab product slice, AIRRAL should focus on lab report creation and report trust, not a complete billing/accounting system. We still need patient/account eligibility and a lightweight payment gate because reports should only be created for an active yearly subscription. But invoice accounting, discounts, dues, GST/accounting, and revenue reports can come after the report workflow is strong.

Report-first MVP should include:

- Patient identity needed for the report.
- Family member selection under the paying account.
- Yearly subscription status check.
- Lightweight electronic payment gate if subscription is inactive.
- Lab order / accession number.
- Selected tests and packages.
- Sample details and timestamps.
- Result entry.
- Formula calculation.
- Abnormal/critical flags.
- Review and signature.
- Report PDF/preview.
- QR or secure report access.
- Delivery log.
- Patient report history.

Billing-later module should include:

- Invoice number.
- Price lists.
- Discounts.
- Payment modes.
- Partial payments / due amount.
- Receipt print/share.
- Daily cash and revenue analytics.
- Referral/doctor commission if the business needs it.

This keeps the first build focused on the part users care about most: producing correct, polished, trusted lab reports.

## Current Intended User Flow

This is the working product flow to design around:

1. Lab tech searches for the patient/account by phone, name, or patient ID.
2. Lab tech chooses the person who needs the test: the account holder or a family member.
3. System checks whether that account has an active yearly subscription.
4. If subscription is inactive, system blocks the next step and opens payment.
5. Payment is electronic only; no manual "paid" checkbox should unlock the flow.
6. After verified payment success, lab tech selects the report/test from the catalog.
7. Lab tech enters the report data into structured fields.
8. System calculates formulas, checks ranges, and flags abnormal/critical values.
9. Lab tech selects or confirms the doctor/referrer.
10. System checks for the doctor folder/workspace and creates it if missing.
11. System automatically adds the finalized report to the right patient history and doctor folder.
12. Pathologist/reviewer finalizes/signs the report.
13. System opens a final action modal: print, download, send to patient, send to doctor, or copy secure report link.

## Payment Gate Design

The payment gate is not full billing. It is an access/eligibility step for report creation.

Recommended approach: integrate a payment gateway / payment aggregator that supports UPI payment links, dynamic UPI QR, webhook verification, settlements, refunds, and later subscriptions/AutoPay. Do not build payment confirmation around a static UPI ID, screenshot upload, or staff-entered transaction note.

Best payment options:

- **Remote patient/family payment link:** primary option because the patient is often not physically present when reports are created. Send a secure payment link by SMS/WhatsApp/email to the account owner or selected payer.
- **Dynamic UPI QR on screen:** best when someone is physically present at the lab. The QR should be tied to a specific payment intent, amount, and account.
- **Lab pays from its account:** allowed when the lab wants to sponsor or cover the yearly subscription for the patient/account. This should still create a verified payment event, not a manual bypass.
- **Optional UPI AutoPay later:** useful for yearly renewals, but only with explicit consent, visible mandate status, and easy cancellation.

Unlock rule:

- AIRRAL should advance from payment only when payment provider status is verified as successful by webhook/API confirmation.
- Screenshots, cash notes, or manual staff confirmation should not unlock the report workflow.

Payment records needed for the report-first MVP:

- `subscriptions`
- `payment_intents`
- `payment_events`
- `payer_accounts`
- `subscription_access_events`

Important states:

- `subscription.status`: active, inactive, pending_payment, expired, cancelled
- `payment_intent.status`: created, link_sent, qr_displayed, processing, succeeded, failed, expired

## Recommended Payment Architecture

Best MVP architecture:

1. AIRRAL creates a `payment_intent` for the yearly subscription amount.
2. AIRRAL calls the payment provider to create a payment link or dynamic UPI QR.
3. AIRRAL stores provider IDs, amount, currency, payer account, patient/family member, and expiry time.
4. Patient/family/lab pays through UPI or another electronic method.
5. Payment provider sends a signed webhook to AIRRAL.
6. AIRRAL verifies the webhook signature and provider event.
7. AIRRAL marks the payment intent as `succeeded`.
8. AIRRAL activates or renews the subscription.
9. AIRRAL unlocks the blocked draft order/report workflow.

Recommended payment priority:

1. **Remote payment link:** default for most cases because patient/family may not be at the lab.
2. **Dynamic UPI QR:** second path for in-lab payment.
3. **Lab-sponsored payment:** same backend payment intent, but payer is the lab account.
4. **UPI AutoPay:** later, for yearly renewal mandates after the one-time payment flow is stable.

Provider requirements:

- UPI payment links.
- Dynamic QR tied to one payment/order.
- Server-side webhooks.
- Webhook signature verification.
- Idempotency / duplicate event handling.
- Refund support.
- Settlement reporting.
- Sandbox/test mode.
- Clear provider transaction IDs that can be shown to support/admin users.

Do not do this:

- Do not unlock from a static QR payment where AIRRAL cannot match the transaction reliably.
- Do not unlock from screenshot upload.
- Do not let lab staff manually mark payment successful except through a restricted admin correction flow with reason, approval, and audit trail.
- Do not start with AutoPay as the default; recurring mandates add consent, support, cancellation, and dispute complexity.

## Flow Review Against Competitors

The flow is good, but it should be tuned for real lab operations.

Compared with Labsmart and Health Amaze, this flow is stronger on membership/access control and family-account handling. Those competitors focus on patient registration, billing, report entry, report delivery, and report access. AIRRAL's differentiator can be: search one account, choose family member, verify yearly subscription, then create a polished report with doctor-folder automation.

Recommended refinement:

- Allow the tech to create a blocked draft order/payment session after selecting the patient/family member.
- Send remote payment link or show dynamic UPI QR from that session.
- Let the tech leave it in a `pending_payment` queue if the patient/family is remote.
- Unlock test selection/result entry only after verified payment success.
- Do not trust screenshots, staff notes, or cash promises.

Why this is better:

- The patient often is not present when the report is created.
- The lab tech should not be stuck on one screen waiting for a remote payer.
- The system still protects revenue because result entry/signing cannot continue until payment is confirmed.
- Every payment attempt, success, failure, expiration, and unlock is auditable.

Keep these guardrails:

- Family members must have their own demographics because age/sex affect reference ranges.
- Doctor folder should be created/selected before finalization, but the report should be added only after signing/finalizing.
- Avoid duplicate doctor folders by matching phone, registration number, clinic, or normalized name where available.
- Doctor access should be permissioned and logged.
- The final modal should appear only after the report is finalized/signed.

## Coverage Strategy

### 1. Cover Breadth With a Seed Catalog

Ship a starter India-focused test catalog, but mark it as seed content that each lab must review.

Initial departments:

- Haematology
- Clinical Biochemistry
- Clinical Pathology
- Serology / Immunology
- Microbiology
- Endocrinology / Hormones
- Histopathology
- Cytopathology

Initial high-volume panels:

- CBC
- LFT
- KFT / RFT
- Lipid profile
- Thyroid profile
- HbA1c
- Blood sugar fasting / PP / random
- Urine routine
- Viral markers
- Dengue / malaria card tests
- Pregnancy profile
- Full body checkup packages

Important: this seed catalog gets labs live faster, but it must not be treated as clinically final. Each lab needs its own reviewed and approved version.

### 2. Cover Depth With Structured Test Definitions

Every test should be data-driven, not hardcoded into components.

Each `lab_test` should support:

- Department
- Sample type
- Method
- Instrument/method note even when machine integration is excluded
- Turnaround target
- Report display name
- Report section
- Package membership
- Whether it is numeric, text, qualitative, culture, or narrative

Each `test_parameter` should support:

- Parameter name
- Unit
- Result type: numeric, text, select, paragraph, table, culture sensitivity
- Decimal precision
- Normal/reference range rules
- Critical value rules
- Formula rule if derived
- Display order
- Method note
- Interpretation note
- Whether it prints on report

Each `reference_range` should support:

- Sex
- Age minimum/maximum
- Unit
- Lower/upper range
- Clinical decision limit
- Pregnancy status if needed
- Method/instrument applicability
- Effective date
- Source/reference note
- Approved by

### 3. Cover Correctness With Validation Workflow

No test, range, formula, or template should become active without review.

Statuses:

- Draft
- Needs review
- Approved
- Active
- Retired

Approval rules:

- A lab owner/admin can create draft tests.
- A qualified pathologist or authorized lab reviewer approves clinical configuration.
- Changes create a new version instead of silently editing live reports.
- Signed reports keep the version of the test/range/template used at signing time.

This matters because reference ranges and formulas can vary by method, instrument, reagent, population, and lab policy.

### 4. Cover Workflow End-to-End

A report feature is not "covered" until it works through the real report path:

1. Register or find patient.
2. Add doctor/referrer if relevant.
3. Create lab order.
4. Select tests/packages.
5. Generate accession/order number and barcode/QR.
6. Mark sample collected/received.
7. Enter results.
8. Auto-calculate formulas.
9. Auto-flag abnormal/critical values.
10. Review and sign.
11. Generate PDF report.
12. Deliver by print/download/WhatsApp/SMS/email.
13. Store in patient history.
14. Update dashboard, audit trail, and delivery log.

## Report Builder Requirements

AIRRAL should use structured report templates first, not a completely freeform designer.

Required template features:

- Lab letterhead
- Patient details
- Patient family/account context when the report is for a family member.
- Order/accession number
- Sample information
- Referrer doctor
- Registered/collected/received/reported timestamps
- Department sections
- Result tables
- Units
- Reference ranges
- Low/high/critical markers
- Method and interpretation notes
- Pathologist/technician signatures
- QR code
- Page numbers
- Optional patient address
- Optional NABL/accreditation display fields when configured by an eligible lab

Narrative report types:

- Histopathology
- Cytology
- Culture and sensitivity
- Radiology-like narrative reports if the product later expands

These need structured headings and reusable snippets, but also narrative entry.

## India Catalog Build Plan

### Phase 1 Catalog

Build around the highest-frequency small-lab workflows:

- CBC with differential
- ESR
- Blood grouping
- Blood sugar F/PP/R
- HbA1c
- LFT
- KFT/RFT
- Lipid profile
- Thyroid profile
- Urine routine
- HBsAg
- HCV
- HIV screen
- Dengue NS1 / IgG / IgM
- Malaria antigen/card
- CRP
- Widal
- Beta HCG
- Vitamin D
- Vitamin B12

### Phase 2 Catalog

Add more depth:

- Coagulation: PT/INR, APTT
- Iron studies
- Electrolytes
- Pancreatic enzymes
- Cardiac markers
- Hormones
- Tumor markers
- Stool routine
- Semen analysis
- Culture and sensitivity

### Phase 3 Catalog

Add advanced/narrative workflows:

- Histopathology
- Cytopathology
- FNAC
- Molecular diagnostics
- Flow cytometry-style structured reports

## Safety And Compliance Guardrails

AIRRAL should not ship "medical truth" as code.

Guardrails:

- All seed ranges clearly marked as templates until reviewed by the lab.
- Lab-specific approval required before active use.
- Formula preview and test cases for calculated fields.
- Critical values require explicit acknowledgement.
- Report signing locks the report version.
- Amendments create revision history.
- Audit logs capture patient edits, test edits, result edits, sign-off, delivery, and report downloads.
- Role permissions separate front desk, technician, pathologist, owner/admin, doctor/referrer, and patient.

NABL direction to keep in mind: NABL accredits medical laboratories under ISO 15189 and publishes specific criteria/guidance documents for medical laboratories. The software should help labs maintain traceability, review, signatures, critical values, and controlled reporting, but the lab remains responsible for clinical validation and accreditation claims.

## Product Acceptance Checklist

Before calling this covered, we should be able to demo these scenarios without custom code:

- Search an existing account and choose either the account holder or a family member.
- Block report creation when the yearly subscription is inactive.
- Send a remote payment link and unlock only after verified electronic payment success.
- Show a dynamic UPI QR for in-lab payment and unlock only after verified payment success.
- Let the lab pay from its account and still create a verified payment event.
- Create a new patient and order CBC + LFT + thyroid profile.
- Generate an accession/order number and QR-backed report access.
- Mark sample collected and received.
- Enter CBC values and have derived values calculate.
- Enter LFT values and see abnormal flags.
- Preview a polished report with units, ranges, notes, timestamps, and signatures.
- Sign and lock the report.
- Auto-create/select the doctor folder and add the report to it.
- Send the report by WhatsApp/SMS/email and track delivery.
- Open the report from patient history.
- Show doctor/referrer contribution in analytics.
- Amend a signed report and see revision/audit history.

## How AIRRAL Can Beat Competitors

The differentiation is not just having 250 tests.

It is making a lab feel calm and in control:

- Cleaner role-based command centers.
- A timeline for every case.
- Status boards for sample, result, signature, payment, and delivery.
- Safer test catalog versioning.
- Better report-delivery history.
- Better patient and doctor access.
- Better audit visibility.
- A report builder that separates clinical logic from visual template design.

## Next Build Move

The first implementation should be the data model and seed catalog shape, not the UI.

Recommended first milestone:

1. Add lab domain types.
2. Add database migration for departments/tests/parameters/reference ranges/packages.
3. Add subscription/payment-intent models for the report gate.
4. Add seed data for 15-20 common tests.
5. Add a result-entry prototype for CBC/LFT/KFT.
6. Add report preview from structured data.
7. Add finalize modal with print/send actions.

Once this works, the UI can be designed around real workflow instead of fake cards.

Billing should start only after this first milestone works end to end.
