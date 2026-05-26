# Pathology Lab Software Competitive Research

Research date: May 15, 2026

Scope: Lab workflow / pathology lab software. Machine/device integration is intentionally excluded except where a vendor explicitly says it is not supported or where the website lists machine pages.

Products researched:

- Labsmart LIS: https://www.labsmartlis.com/
- Health Amaze: https://healthamaze.app/

## High-Level Pattern

Both products position themselves as simple cloud pathology lab management systems, not only as report generators.

The core workflow they cover:

1. Patient registration
2. Billing / invoice creation
3. Test catalog / package selection
4. Lab report entry
5. Calculated values and abnormal flagging
6. Pathologist / technician signature
7. Report delivery by print, WhatsApp, SMS, email, and QR code
8. Patient online access
9. Staff permissions and activity tracking
10. Business analytics / reporting
11. Data export / ownership

## Labsmart LIS

### Positioning

Labsmart focuses heavily on ease of adoption for Indian pathology labs. Their message is that even non-technical receptionists or technicians can start billing/reporting quickly.

Public claims and evidence:

- Used in 2,500+ labs and 2 crore+ reports.
- Cloud-based pathology software.
- Pricing starts around Rs.417/month when billed yearly.
- Support and onboarding are prominent parts of the sales motion.

### Main Software Modules

**Patient registration and billing**

- Register patients.
- Generate bills.
- Automatic amount, discount, and balance calculations.
- Integrated billing-to-report flow: creating a bill creates the report, and bill investigation changes update reports.
- Tamper-proof billing controls: date/time auto-recorded, case registration number cannot be changed, edits tracked, and bills linked to logged-in staff.
- Mobile registration for home collection.
- Barcode bills for tracking.
- QR-coded bills so patients can check report status and download report PDFs.
- Payment mode tracking: cash, UPI, card, insurance.
- Bill customization with lab logo/name/address.

Sources: Labsmart patient registration and billing page, pricing page.

**Lab reporting**

- Technician enters results into preconfigured tests.
- Normal values and interpretations can be updated from the report page.
- Report sharing by WhatsApp, email, and SMS.
- QR code on reports for PDF download and sharing.
- Test packages such as full body checkup, pregnancy profile, pre-surgical package.
- Ready test database with normal values and units.
- Age/gender based normal values.
- Add/edit tests, panels, and existing formats.
- Auto calculations for CBC, lipid profile, LFT, KFT, and calculated parameters.
- High-quality PDF reports with/without letterhead.
- E-signature support for lab technician and doctor/pathologist.
- Method, instrument, and interpretation notes printed on reports.
- Show/hide fields, PDF font size, spacing.
- Report state tracking: new, in-progress, final, signed off, printed, and pending payments.

Sources: Labsmart lab reporting page.

**Patient communication**

- Reports delivered by WhatsApp, SMS, email.
- QR code report/bill access.
- Welcome SMS, bill SMS, report-ready SMS.
- Automated SMS when reports are ready.

Sources: Labsmart lab reporting and patient experience sections.

**Business operations**

- Business analysis: revenue by case type, total cases, billing, income, trend graph.
- Test count analysis.
- Expense management.
- Referral/doctor business management is mentioned in FAQ and pricing.
- Doctor portal in premium plan.
- Data export in premium plan.

Sources: Labsmart business analysis, pricing, FAQ.

**Staff control and audit**

- Owner account and employee account model.
- Owner can add/block employees and configure permissions.
- Permissions protect sensitive data such as rate lists, IP share percentage, revenue, reference ranges, letterhead, case deletion/modification.
- Activity tracking records who changed what and when.
- Tracks patient detail edits, discount edits, doctor information updates, investigation changes.
- Used for accountability, error detection, fraud prevention, and audit readiness.

Sources: Labsmart user accounts/permissions and activity tracking pages.

**Use cases and workflow coverage**

- Package management.
- Mediclaim / TPA.
- Pathologist and technician signatures.
- Non-working-hours report delivery.
- Home sample collection.
- Online report delivery.
- Employee accountability.
- Online lab monitoring.
- SMS campaign.
- Reduced bill printing.

Sources: Labsmart use case lists.

### What Labsmart Emphasizes

- Very low barrier to start.
- Support/onboarding with real people.
- Billing + reporting integration.
- Report format depth and broad department coverage.
- Audit/accountability to prevent fraud.
- Operational management: billing, reporting, staff, business, referral, expense, exports.

## Health Amaze

### Positioning

Health Amaze positions itself as modern, cloud-based, easy, patient-friendly, and low cost. Their strongest story is clean digital reporting plus patient access.

Public claims and evidence:

- Cloud pathology LIMS.
- Start printing bills/reports quickly.
- Plans start at Rs.499/month.
- Patient and business login are separate.
- Explicit FAQ says device interfacing is currently not supported.

### Main Software Modules

**Patient registration and billing**

- Patient onboarding and billing in one place.
- Quick onboarding screens.
- Barcode on lab bills.
- Auto-increment bill IDs.
- Payment modes: UPI, cash, card.
- Test catalog with 250+ diagnostic lab tests included.
- Bill print customization.
- Patient search by mobile number.
- Patient history: historical bills and reports.
- Bills can be edited after creation: add tests, discounts, phone updates.

Sources: Health Amaze billing and patient onboarding page.

**Lab report management**

- Report builder for 250+ tests.
- Smart formulas and auto-calculation.
- Abnormality flagging based on reference ranges, age, and gender.
- Pathologist/technician digital signatures.
- Custom test packages.
- Reusable interpretation notes, methodology references, and clinical notes.
- Test library with reference ranges, parameters, units, and formulas.
- One-click signature and pathologist sign-off.
- Digital report format customization: letterheads, font, spacing, test hierarchy, packages, multi-department formats, normal value ranges.
- Audit trail for signatures.

Sources: Health Amaze lab report management page and homepage.

**Patient report delivery and access**

- Reports delivered by WhatsApp, email, SMS.
- Branded PDF reports.
- Auto-send on pathologist sign-off.
- Patient channel preferences.
- Bulk sending for batch reports.
- Delivery status tracking.
- Unique QR code per report.
- QR scan, download, share.
- Tamper-proof/verifiable online report.
- Patient EHR / digital health records across visits.
- 24/7 report access.
- Patient can forward reports to family/doctors.
- Delivery analytics: status, opened/downloaded, channel effectiveness, engagement.

Sources: Health Amaze WhatsApp/SMS/email report delivery and online access pages.

**Analytics**

- Business intelligence around revenue, discounts, profit, audit reports.
- Daily reports, monthly reports, due reports, referral reports, test analysis.
- Pricing page also lists business analytics in basic plan.

Sources: Health Amaze homepage/features/pricing.

**Staff, permissions, and operations**

- Staff logins in Standard plan.
- Permissions restrictions.
- Admin password restrictions.
- Login and activity tracker.
- Department-wise signatures.
- Doctor/referral reports.
- Personalized WhatsApp number.
- Outsourced PDF attachment.

Sources: Health Amaze pricing page.

**Multi-location / chain workflows**

- Advanced plan includes collection center portal.
- Collection center rate lists.
- Pay-later tracking.
- Patient phone export.
- Advanced data exports.
- Enquiry / appointment management.
- FAQ says cloud approach can grow from one user to multiple users and branches.

Sources: Health Amaze pricing and FAQ.

### What Health Amaze Emphasizes

- Modern patient experience.
- Digital access and QR report verification.
- Report automation: formulas, abnormal flags, signatures, interpretations.
- Easy setup and lower training burden.
- Cloud/mobile access.
- Transparent pricing and data ownership.
- It explicitly does not currently support device integration.

## Side-By-Side Capability Map

| Capability | Labsmart LIS | Health Amaze |
|---|---|---|
| Patient registration | Yes | Yes |
| Billing | Yes | Yes |
| Report creation | Yes | Yes |
| Test catalog | Yes, common test formats | Yes, 250+ tests |
| Packages / profiles | Yes | Yes |
| Normal ranges | Yes | Yes |
| Age/gender restrictions | Yes | Yes |
| Auto calculations | Yes | Yes |
| Abnormal value alerts | Yes | Yes |
| Digital signatures | Yes | Yes |
| QR-coded reports | Yes | Yes |
| QR-coded bills | Yes | Yes / barcode + QR listed in pricing |
| WhatsApp/SMS/email reports | Yes | Yes |
| Patient online access | Yes | Strong focus |
| Patient EHR/history | Patient past reports/history; Health Amaze makes EHR more explicit | Strong focus |
| Home collection | Yes | Not emphasized on pages reviewed |
| Referral doctor business | Yes | Doctor/referral reports |
| Staff permissions | Yes | Yes |
| Activity audit | Yes | Yes |
| Business analytics | Yes | Yes |
| Data export | Yes | Yes |
| Multi-branch / collection centers | Less explicit in pages reviewed | Advanced plan includes collection center workflows |
| Machine/device integration | Labsmart has machine pages, but excluded here | Explicitly says not supported |

## Product Lessons for Our Path Lab Software

Do not make the first product only about reports. The successful software path is an operating system for the lab.

Recommended modules:

1. **Front desk**
   - Patient registration
   - Bill creation
   - Payments, discount, balance due
   - QR/barcoded bill
   - Patient search/history

2. **Sample and case workflow**
   - Case ID / accession number
   - Collection/received/reported TAT fields
   - Status: registered, collected, received, in reporting, signed, delivered

3. **Report workspace**
   - Department/test/panel management
   - Normal ranges by age/gender
   - Formula engine
   - Abnormal value flags
   - Interpretation/method/instrument notes
   - PDF/report template customization
   - Digital pathologist/technician signature

4. **Patient delivery**
   - WhatsApp/SMS/email report delivery
   - QR report download
   - Report-ready notifications
   - Patient portal / patient history

5. **Owner/admin**
   - Staff accounts and permissions
   - Activity log
   - Fraud/audit flags
   - Business analytics
   - Data export

6. **Growth workflows**
   - Referral doctor management
   - Packages / health checkups
   - Home collection
   - Collection center / branch support
   - Enquiry/appointment management

## Competitive Gaps / Opportunities

These are places we can potentially do better:

- Cleaner, less crowded UI for daily lab users.
- Role-based home screens: receptionist, technician, pathologist, owner.
- A single case timeline from registration to delivery.
- Clear status boards for pending sample, pending report, pending signature, pending payment, delivered.
- Better patient conversation history, not just report sending.
- Better doctor/referral workspace.
- Better owner dashboard with alerts, not only charts.
- Smarter audit flags: suspicious discounts, frequent patient edits, deleted/changed tests.
- Guided setup checklist by lab type: small pathology, diagnostic center, radiology add-on, collection center.
- Report builder that separates template design, parameter logic, and signing workflow cleanly.

## Sources

- Labsmart homepage: https://www.labsmartlis.com/
- Labsmart patient registration/billing: https://www.labsmartlis.com/features/patient_registration_and_billing
- Labsmart lab reporting: https://www.labsmartlis.com/features/lab_reporting
- Labsmart WhatsApp/SMS/email reports: https://www.labsmartlis.com/whatsapp-sms-email-lab-report
- Labsmart business analysis: https://www.labsmartlis.com/features/business_analysis
- Labsmart user permissions: https://www.labsmartlis.com/features/user_accounts_and_permissions
- Labsmart activity tracking: https://www.labsmartlis.com/features/activity_tracking
- Health Amaze homepage: https://healthamaze.app/
- Health Amaze features: https://healthamaze.app/features
- Health Amaze patient registration/billing: https://healthamaze.app/features/billing-and-patient-onboarding/
- Health Amaze lab report management: https://healthamaze.app/features/lab-report-management
- Health Amaze report delivery: https://healthamaze.app/features/whatsapp-sms-email-lab-report
- Health Amaze online access: https://healthamaze.app/features/online-access
- Health Amaze pricing: https://healthamaze.app/pricing
