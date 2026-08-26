#!/usr/bin/env bash
#
# Finishes standing up the AIRRAL POC on the existing GCP project.
#
# The project (airral-a0e81 / "airral-dev") already has billing linked, an
# Artifact Registry repo, a Workload Identity pool wired to this GitHub repo,
# and a github-actions deployer service account. This script adds only what is
# still missing: Cloud SQL, Secret Manager, the Cloud Run runtime identity, the
# extra deployer roles, and a budget alert.
#
# Safe to re-run: every step is idempotent, so a failure part-way through can be
# fixed and the script run again without cleaning up first.
#
# Prerequisites:
#   gcloud auth login    (as an account with owner/editor on the project)
#
# Usage:
#   ./infra/gcp/setup.sh

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-airral-a0e81}"
REGION="${REGION:-us-central1}"
SQL_INSTANCE="${SQL_INSTANCE:-airral-db}"
DB_NAME="${DB_NAME:-airral}"
DB_USER="${DB_USER:-airral_app}"
BUDGET_AMOUNT="${BUDGET_AMOUNT:-25}"
GITHUB_REPO="${GITHUB_REPO:-Airral/airral}"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }

gcloud config set project "$PROJECT_ID" >/dev/null
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
BILLING_ACCOUNT="$(gcloud billing projects describe "$PROJECT_ID" \
  --format='value(billingAccountName)' | sed 's|billingAccounts/||')"

echo "project: $PROJECT_ID ($PROJECT_NUMBER)"
echo "billing: $BILLING_ACCOUNT"
echo "region:  $REGION"

# ---------------------------------------------------------------------------
say "1/6  Enable the APIs that are still missing"
# ---------------------------------------------------------------------------
# run / artifactregistry / firebasehosting / sts / iamcredentials are already on.
gcloud services enable \
  sqladmin.googleapis.com \
  secretmanager.googleapis.com \
  cloudbilling.googleapis.com \
  billingbudgets.googleapis.com

# ---------------------------------------------------------------------------
say "2/6  Budget alert at \$${BUDGET_AMOUNT}/month"
# ---------------------------------------------------------------------------
# Set before creating billable resources so a misconfiguration cannot quietly
# run up a bill. Alerts email the billing account admins.
if ! gcloud billing budgets list --billing-account="$BILLING_ACCOUNT" \
      --format='value(displayName)' 2>/dev/null | grep -qx "airral-poc-budget"; then
  gcloud billing budgets create \
    --billing-account="$BILLING_ACCOUNT" \
    --display-name="airral-poc-budget" \
    --budget-amount="${BUDGET_AMOUNT}USD" \
    --filter-projects="projects/${PROJECT_NUMBER}" \
    --threshold-rule=percent=0.5 \
    --threshold-rule=percent=0.9 \
    --threshold-rule=percent=1.0
else
  echo "budget already exists"
fi

# ---------------------------------------------------------------------------
say "3/6  Cloud SQL Postgres (takes ~5-10 minutes on first run)"
# ---------------------------------------------------------------------------
# db-f1-micro, zonal, no HA: ~\$11/month, the dominant cost of the POC.
# --edition=ENTERPRISE is required: new instances now default to
# ENTERPRISE_PLUS, which rejects shared-core tiers and costs far more.
#
# Backups run at 15:00 UTC, which is inside the 08:00-20:00 ET window that
# gcp-cloudsql-power.yml keeps the instance up for. A stopped instance skips its
# automated backups entirely, so a window outside those hours never fires.
#
# The instance keeps its default public IP but gets NO authorized networks, so
# no raw IP can reach it. All access goes through the Cloud SQL connectors,
# which authenticate with IAM and an ephemeral client certificate. That is what
# lets Cloud Run and GitHub Actions share one connection config.
if ! gcloud sql instances describe "$SQL_INSTANCE" >/dev/null 2>&1; then
  gcloud sql instances create "$SQL_INSTANCE" \
    --database-version=POSTGRES_16 \
    --edition=ENTERPRISE \
    --tier=db-f1-micro \
    --region="$REGION" \
    --storage-size=10GB \
    --storage-type=SSD \
    --backup \
    --backup-start-time=15:00 \
    --availability-type=zonal
else
  echo "instance already exists"
fi

gcloud sql databases create "$DB_NAME" --instance="$SQL_INSTANCE" 2>/dev/null \
  || echo "database already exists"

DB_PASSWORD=""
if gcloud sql users list --instance="$SQL_INSTANCE" --format='value(name)' | grep -qx "$DB_USER"; then
  echo "user $DB_USER exists; leaving its password alone"
else
  DB_PASSWORD="$(openssl rand -base64 32 | tr -d '\n/+=' | head -c 32)"
  gcloud sql users create "$DB_USER" --instance="$SQL_INSTANCE" --password="$DB_PASSWORD"
fi

INSTANCE_CONNECTION_NAME="$(gcloud sql instances describe "$SQL_INSTANCE" \
  --format='value(connectionName)')"

# ---------------------------------------------------------------------------
say "4/6  Secrets"
# ---------------------------------------------------------------------------
create_secret() {  # name, value
  local name="$1" value="$2"
  if ! gcloud secrets describe "$name" >/dev/null 2>&1; then
    gcloud secrets create "$name" --replication-policy=automatic
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=-
    echo "created secret $name"
  else
    echo "secret $name already exists; not overwriting"
  fi
}

[[ -n "$DB_PASSWORD" ]] && create_secret db-password "$DB_PASSWORD"
# Must be 32+ bytes and must not be the dev default: JwtTokenProvider rejects
# both at startup, so a bad value here fails the deploy rather than shipping.
create_secret jwt-encryption-secret "$(openssl rand -base64 48 | tr -d '\n')"
# google-oauth-client-id is deliberately NOT created here: Secret Manager rejects
# an empty payload, and a secret with no version cannot be mounted. Create it when
# you actually configure Google sign-in:
#   gcloud secrets create google-oauth-client-id --replication-policy=automatic
#   printf '%s' "<client-id>" | gcloud secrets versions add google-oauth-client-id --data-file=-
# The app treats a blank GOOGLE_OAUTH_CLIENT_ID as "Google sign-in disabled".

# ---------------------------------------------------------------------------
say "5/6  Service accounts and roles"
# ---------------------------------------------------------------------------
API_SA="airral-api@${PROJECT_ID}.iam.gserviceaccount.com"
DEPLOY_SA="github-actions@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud iam service-accounts create airral-api \
  --display-name="AIRRAL Cloud Run runtime" 2>/dev/null || echo "airral-api exists"

# Runtime: reach the database, read its own secrets. Nothing else.
for role in roles/cloudsql.client roles/secretmanager.secretAccessor; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${API_SA}" --role="$role" --condition=None >/dev/null
done

# Deployer already has run.admin + artifactregistry.writer. It additionally
# needs to run the sync job against the database, read secrets, deploy Cloud Run
# services that run *as* airral-api, and publish the frontends.
for role in roles/cloudsql.client roles/secretmanager.secretAccessor \
            roles/iam.serviceAccountUser roles/firebasehosting.admin; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${DEPLOY_SA}" --role="$role" --condition=None >/dev/null
done
# Starting and stopping the database on a schedule needs instances.update.
# A custom role rather than roles/cloudsql.editor: the deployer is reachable
# from a public repository's Actions, so it gets the two permissions it needs
# and no access to schemas, users, backups or databases.
if ! gcloud iam roles describe airralSqlPowerToggle --project="$PROJECT_ID" >/dev/null 2>&1; then
  cat > /tmp/airral-sqlpower-role.yaml <<'ROLE'
title: "AIRRAL Cloud SQL power toggle"
description: "Start and stop the POC database on a schedule."
stage: "GA"
includedPermissions:
- cloudsql.instances.get
- cloudsql.instances.list
- cloudsql.instances.update
ROLE
  gcloud iam roles create airralSqlPowerToggle --project="$PROJECT_ID" \
    --file=/tmp/airral-sqlpower-role.yaml >/dev/null
  echo "created custom role airralSqlPowerToggle"
fi
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${DEPLOY_SA}" \
  --role="projects/${PROJECT_ID}/roles/airralSqlPowerToggle" --condition=None >/dev/null

echo "roles granted"

# ---------------------------------------------------------------------------
say "6/6  Done - set these GitHub repository variables"
# ---------------------------------------------------------------------------
# Workload Identity is already configured and, as of 2026-08-26, scoped to this
# repository only (assertion.repository == 'Airral/airral'). The downloadable
# service-account key that predated it has been deleted. Do not recreate one.
WIF_PROVIDER="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/github/providers/github-provider"

cat <<SUMMARY

  gh variable set GCP_PROJECT_ID     --body "${PROJECT_ID}"
  gh variable set GCP_REGION         --body "${REGION}"
  gh variable set GCP_WIF_PROVIDER   --body "${WIF_PROVIDER}"
  gh variable set GCP_DEPLOY_SA      --body "${DEPLOY_SA}"
  gh variable set GCP_API_SA         --body "${API_SA}"
  gh variable set CLOUD_SQL_INSTANCE --body "${INSTANCE_CONNECTION_NAME}"
  gh variable set DB_NAME            --body "${DB_NAME}"
  gh variable set DB_USER            --body "${DB_USER}"

These are repository *variables*, not secrets - none is sensitive, and the real
credentials stay in Secret Manager. The database password was written to Secret
Manager as 'db-password' and is deliberately not printed here.

SUMMARY
