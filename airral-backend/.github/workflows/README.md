# Backend CI/CD Workflows

This directory contains GitHub Actions workflows for the Airral backend.

## Workflows

### 1. Build & Test (`build.yml`)

**Triggers:**
- Push to `main` or `develop` branches (when backend files change)
- Pull requests to `main` or `develop` (when backend files change)

**What it does:**
- Spins up a PostgreSQL test database
- Builds the Spring Boot application
- Runs unit and integration tests
- Generates test reports and coverage
- Uploads build artifacts

**Requirements:**
- None (runs automatically)

---

### 2. Release (`release.yml`)

**Triggers:**
- Push of a tag matching `backend-v*.*.*` (e.g., `backend-v1.0.0`)

**What it does:**
- Builds production JAR
- Creates a GitHub release with changelog
- Optionally builds and pushes Docker image

**How to create a release:**
```bash
git tag backend-v1.0.0
git push origin backend-v1.0.0
```

**Pre-release versions:**
Tags containing `alpha` or `beta` will be marked as pre-release:
```bash
git tag backend-v1.0.0-beta.1
git push origin backend-v1.0.0-beta.1
```

---

### 3. Deploy (`deploy.yml`)

**Triggers:**
- Manual trigger via GitHub Actions UI

**Inputs:**
- `environment`: Choose `staging` or `production`
- `version`: Version number to deploy (e.g., `1.0.0`)

**What it does:**
- Builds production JAR
- Deploys to AWS Elastic Beanstalk
- Verifies deployment health
- Sends Slack notification

**How to deploy:**
1. Go to GitHub Actions → Deploy workflow
2. Click "Run workflow"
3. Select environment and enter version
4. Click "Run workflow"

**Required Secrets:**
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- `SLACK_WEBHOOK` (optional)

---

### 4. Security Scan (`security-scan.yml`)

**Triggers:**
- Weekly schedule (Monday 2 AM)
- Push to `main` branch
- Manual trigger

**What it does:**
- Scans dependencies for vulnerabilities
- Runs Trivy security scanner
- Runs Snyk analysis
- Reports findings to GitHub Security

**Required Secrets:**
- `SNYK_TOKEN` (optional, for Snyk scanning)

---

## Environment Variables

### Required for all environments:
```bash
SPRING_R2DBC_URL=r2dbc:postgresql://host:5432/airral_db
SPRING_R2DBC_USERNAME=username
SPRING_R2DBC_PASSWORD=password
SPRING_FLYWAY_URL=jdbc:postgresql://host:5432/airral_db
SPRING_FLYWAY_USER=username
SPRING_FLYWAY_PASSWORD=password
JWT_SECRET=your-256-bit-secret-key
CORS_ALLOWED_ORIGINS=https://www.airral.com,https://app.airral.com,https://apply.airral.com,https://admin.airral.com
```

### Optional:
```bash
MAIL_USERNAME=notifications@airral.com
MAIL_PASSWORD=app-specific-password
```

---

## GitHub Environments

Configure these in GitHub Settings → Environments:

### Staging
- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_ACCESS_KEY`
- Database credentials
- Other environment-specific secrets

### Production
- Same as staging but with production values
- Enable required reviewers for extra safety

---

## Docker Support

The release workflow can build and push Docker images. To enable:

1. Set up Docker Hub credentials:
   - `DOCKER_USERNAME`
   - `DOCKER_PASSWORD`

2. Create a `Dockerfile` in the backend root:
```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

3. Stable releases will automatically build and push to Docker Hub

---

## Monitoring Builds

- **Status Badges**: Add to README.md
```markdown
![Build](https://github.com/your-org/airral/workflows/Backend%20Build/badge.svg)
```

- **Test Reports**: View in Actions → Build run → Test Results
- **Coverage**: Automatically uploaded to Codecov
- **Security Scans**: View in Security → Code scanning alerts

---

## Local Development

Run the same checks locally:

```bash
# Build
./gradlew clean build

# Tests
./gradlew test

# Security scan
./gradlew dependencyCheckAnalyze
```
