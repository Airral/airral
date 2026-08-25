# Tomorrow Launch Readiness

Goal: let event users create an applicant account, sign in, find real jobs, read job-market news, upload a resume, and save/check roles without needing social/feed features.

## Must Work Before Demo

- Website runs on `http://localhost:4200`.
- Applicant portal runs on `http://localhost:4201`.
- Backend runs on `http://localhost:8080`.
- Candidate account creation works from `/apply`.
- Email/password login works from `/login`.
- Google sign-in is configured with `GOOGLE_OAUTH_CLIENT_ID` and the same client ID is exposed to the website runtime config.
- Public jobs endpoint returns roles: `/api/candidate/jobs/recommended/page?limit=20`.
- Public news endpoint returns market context: `/api/feed/news?category=TECH&size=30`.
- Applicant portal `Jobs` view loads first.
- Applicant portal `News` tab only fetches news after the user clicks it.
- Resume upload accepts PDF/DOCX under the configured size limit.
- Saved jobs and resume fit degrade gracefully if a secondary API is unavailable.

## Google Sign-In Setup

Use Google Identity Services for the browser button and send the returned ID token to AIRRAL:

- Backend endpoint: `POST /api/auth/google`
- Backend env: `GOOGLE_OAUTH_CLIENT_ID`
- Website runtime config: `window.AIRRAL_RUNTIME_CONFIG.googleClientId`
- Local fallback for testing: set `AIRRAL_GOOGLE_CLIENT_ID` in browser local storage.

Google OAuth client allowed JavaScript origins should include:

- `http://localhost:4200`
- `https://www.airral.com`

If production uses `apply.airral.com` for the login/signup page later, add it too.

## Launch Order

1. Restart backend after the Google auth code is deployed.
2. Run migrations against the launch database.
3. Start website and applicant portal on fixed ports.
4. Create one test applicant with email/password.
5. Create/sign in with one Gmail account.
6. Open applicant portal and verify Jobs, News, Resume upload, Save job, and Resume fit.
7. Keep rooms, founder spaces, events, and social feed out of the primary demo path.

## Known Follow-Ups

- Move resume file storage from local disk to a GCP bucket before public scale.
- Add persistent Google identity mapping by provider subject if we want stronger account linking than email matching.
- Add a production smoke test that hits jobs, news, auth, profile, resume upload, and saved jobs after every deploy.
