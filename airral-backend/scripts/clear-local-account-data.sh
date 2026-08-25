#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Clear local AIRRAL account/login data while preserving external job source cache data.

Usage:
  ./scripts/clear-local-account-data.sh [--yes]

Environment:
  DB_NAME      Database name. Defaults to airral_db.
  DB_USER      Optional PostgreSQL user.
  DB_HOST      Optional PostgreSQL host.
  DB_PORT      Optional PostgreSQL port.
  DB_PASSWORD  Optional PostgreSQL password. Passed as PGPASSWORD.

This clears:
  users, credentials, roles, invitations, organizations, departments,
  organization jobs, applications, candidate profiles, saved jobs,
  resume documents, fit results, feed/account artifacts, and related IDs.

This preserves:
  external_job_postings, external_job_sources, external company/enrichment tables,
  and Flyway migration history.
USAGE
}

CONFIRM=false
for arg in "$@"; do
  case "$arg" in
    --yes|-y)
      CONFIRM=true
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      usage >&2
      exit 2
      ;;
  esac
done

DB_NAME="${DB_NAME:-airral_db}"
PSQL=(psql -v ON_ERROR_STOP=1 -d "$DB_NAME")

if [[ -n "${DB_USER:-}" ]]; then
  PSQL+=(-U "$DB_USER")
fi

if [[ -n "${DB_HOST:-}" ]]; then
  PSQL+=(-h "$DB_HOST")
fi

if [[ -n "${DB_PORT:-}" ]]; then
  PSQL+=(-p "$DB_PORT")
fi

if [[ -n "${DB_PASSWORD:-}" ]]; then
  export PGPASSWORD="$DB_PASSWORD"
fi

echo "Database: $DB_NAME"
echo "This will DELETE local AIRRAL account/login data and reset local account IDs."
echo "External job cache/source tables will be preserved."

if [[ "$CONFIRM" != true ]]; then
  read -r -p "Type CLEAR to continue: " answer
  if [[ "$answer" != "CLEAR" ]]; then
    echo "Cancelled."
    exit 0
  fi
fi

"${PSQL[@]}" <<'SQL'
BEGIN;

DELETE FROM feed_reactions;
DELETE FROM feed_comments;
DELETE FROM feed_posts;
DELETE FROM company_follows;
DELETE FROM candidate_job_fit_results;
DELETE FROM candidate_resume_documents;
DELETE FROM candidate_saved_jobs;
DELETE FROM candidate_profiles;
DELETE FROM activity_feed;
DELETE FROM hr_encounters;
DELETE FROM audit_logs;
DELETE FROM offers;
DELETE FROM interviews;
DELETE FROM referrals;
DELETE FROM application_activities;
DELETE FROM application_notes;
DELETE FROM applications;
DELETE FROM jobs;
DELETE FROM user_invitations;
DELETE FROM user_roles;
DELETE FROM user_credentials;
DELETE FROM users;
DELETE FROM departments;
DELETE FROM organizations;

ALTER SEQUENCE IF EXISTS users_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS candidate_profiles_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS candidate_saved_jobs_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS candidate_resume_documents_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS candidate_job_fit_results_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS organizations_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS departments_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS jobs_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS applications_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS application_notes_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS application_activities_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS referrals_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS interviews_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS offers_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS audit_logs_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS hr_encounters_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS activity_feed_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS feed_posts_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS feed_comments_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS feed_reactions_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS company_follows_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_credentials_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_roles_id_seq RESTART WITH 1;
ALTER SEQUENCE IF EXISTS user_invitations_id_seq RESTART WITH 1;

COMMIT;
SQL

echo
echo "Account data cleared. Current counts:"
"${PSQL[@]}" <<'SQL'
SELECT 'users' table_name, count(*) FROM users
UNION ALL SELECT 'candidate_profiles', count(*) FROM candidate_profiles
UNION ALL SELECT 'organizations', count(*) FROM organizations
UNION ALL SELECT 'candidate_saved_jobs', count(*) FROM candidate_saved_jobs
UNION ALL SELECT 'candidate_resume_documents', count(*) FROM candidate_resume_documents
UNION ALL SELECT 'applications', count(*) FROM applications
UNION ALL SELECT 'jobs', count(*) FROM jobs
UNION ALL SELECT 'external_job_postings', count(*) FROM external_job_postings
UNION ALL SELECT 'external_job_sources', count(*) FROM external_job_sources
ORDER BY table_name;
SQL
