# Frontend CI/CD Workflows

This directory contains GitHub Actions workflows for the Airral frontend (Nx monorepo).

## Workflows

### 1. Build & Test (`build.yml`)

**Triggers:**
- Push to `main` or `develop` branches (when frontend files change)
- Pull requests to `main` or `develop` (when frontend files change)

**What it does:**
- Builds all 4 apps in parallel:
  - Website (`www.airral.com`)
  - HR Portal (`app.airral.com`)
  - Applicant Portal (`apply.airral.com`)
  - Admin Portal (`admin.airral.com`)
- Runs linting for each app
- Tests all shared libraries
- Uploads build artifacts and coverage

**Requirements:**
- None (runs automatically)

---

### 2. Release (`release.yml`)

**Triggers:**
- Push of a tag matching `frontend-v*.*.*` (e.g., `frontend-v1.0.0`)

**What it does:**
- Builds all 4 apps for production
- Creates compressed archives for each app
- Creates a GitHub release with all artifacts

**How to create a release:**
```bash
git tag frontend-v1.0.0
git push origin frontend-v1.0.0
```

**Pre-release versions:**
```bash
git tag frontend-v1.0.0-beta.1
git push origin frontend-v1.0.0-beta.1
```

---

### 3. Deploy (`deploy.yml`)

**Triggers:**
- Manual trigger via GitHub Actions UI

**Inputs:**
- `environment`: Choose `staging` or `production`
- `apps`: Choose which apps to deploy:
  - `all` (default)
  - `website`
  - `hr-portal`
  - `applicant-portal`
  - `admin-portal`
  - Or comma-separated: `website,hr-portal`

**What it does:**
- Builds selected apps for production
- Deploys to AWS S3 buckets
- Invalidates CloudFront cache
- Verifies deployment
- Sends Slack notification

**How to deploy:**
1. Go to GitHub Actions → Deploy workflow
2. Click "Run workflow"
3. Select environment and apps
4. Click "Run workflow"

**Deployment Examples:**

Deploy everything to staging:
```
Environment: staging
Apps: all
```

Deploy only website to production:
```
Environment: production
Apps: website
```

Deploy HR and Applicant portals:
```
Environment: production
Apps: hr-portal,applicant-portal
```

**Required Secrets:**
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `CLOUDFRONT_WEBSITE_ID`
- `CLOUDFRONT_HR_PORTAL_ID`
- `CLOUDFRONT_APPLICANT_PORTAL_ID`
- `CLOUDFRONT_ADMIN_PORTAL_ID`
- `SLACK_WEBHOOK` (optional)

---

### 4. Security & Quality (`security-quality.yml`)

**Triggers:**
- Weekly schedule (Monday 3 AM)
- Push to `main` branch
- Manual trigger

**What it does:**
- Runs `npm audit` for dependency vulnerabilities
- Runs Snyk security scanning
- Performs Lighthouse performance audits
- Analyzes bundle sizes

**Required Secrets:**
- `SNYK_TOKEN` (optional)

---

## Apps Overview

| App | URL | Purpose |
|-----|-----|---------|
| Website | `https://www.airral.com` | Marketing site & job board |
| HR Portal | `https://app.airral.com` | HR/Manager dashboard |
| Applicant Portal | `https://apply.airral.com` | Job seeker portal |
| Admin Portal | `https://admin.airral.com` | Platform admin tools |

---

## AWS S3 Bucket Structure

### Staging
```
s3://airral-staging-website/
s3://airral-staging-hr-portal/
s3://airral-staging-applicant-portal/
s3://airral-staging-admin-portal/
```

### Production
```
s3://airral-production-website/
s3://airral-production-hr-portal/
s3://airral-production-applicant-portal/
s3://airral-production-admin-portal/
```

---

## CloudFront Distributions

Each app should have its own CloudFront distribution pointing to its S3 bucket:

1. **Website**: `www.airral.com` → CloudFront → `airral-production-website`
2. **HR Portal**: `app.airral.com` → CloudFront → `airral-production-hr-portal`
3. **Applicant Portal**: `apply.airral.com` → CloudFront → `airral-production-applicant-portal`
4. **Admin Portal**: `admin.airral.com` → CloudFront → `airral-production-admin-portal`

---

## GitHub Environments

Configure these in GitHub Settings → Environments:

### Staging
- All AWS credentials and CloudFront IDs
- No approval required

### Production
- Same credentials as staging but for production resources
- **Enable required reviewers** for safety

---

## Local Development

Run the same checks locally:

```bash
# Install dependencies
npm ci

# Build all apps
npx nx run-many --target=build --all --configuration=production

# Build specific app
npx nx build website --configuration=production

# Lint
npx nx lint website

# Test
npx nx test shared-api

# Check bundle sizes
npx nx run-many --target=build --all --configuration=production
du -sh dist/apps/*
```

---

## Environment Configuration

**No environment files needed!** The apps automatically detect production vs development based on the hostname:

- `localhost:*` → Development mode → Uses `http://localhost:8080/api`
- `*.airral.com` → Production mode → Uses `https://api.airral.com`

This is configured in `libs/shared-utils/src/lib/constants.ts`.

---

## Performance Budgets

Recommended max bundle sizes:

| App | Initial Load | Total |
|-----|-------------|-------|
| Website | < 500 KB | < 2 MB |
| HR Portal | < 800 KB | < 4 MB |
| Applicant Portal | < 600 KB | < 3 MB |
| Admin Portal | < 800 KB | < 4 MB |

Monitor with the bundle analysis job.

---

## Cache Strategy

The deploy workflow uses a smart caching strategy:

1. **Static assets** (JS, CSS, images): `Cache-Control: public, max-age=31536000, immutable`
   - Cached for 1 year (file names include content hash)

2. **HTML and JSON**: `Cache-Control: public, max-age=0, must-revalidate`
   - Always fresh (allows instant updates)

3. **CloudFront invalidation**: `/*`
   - Clears CDN cache after deployment

---

## Monitoring Builds

- **Status Badges**:
```markdown
![Build](https://github.com/your-org/airral/workflows/Frontend%20Build/badge.svg)
```

- **Test Coverage**: Uploaded to Codecov
- **Lighthouse Scores**: Available in Actions artifacts
- **Bundle Sizes**: Shown in Actions summary
