#!/usr/bin/env bash
# Proves the sync upsert cannot destroy description-derived data.
#
# Written after an audit found the pipeline was deriving real values -- employer
# pay ranges, sponsorship language, experience years, the enriched search vector
# -- writing them, and then overwriting them with placeholders on the next
# four-hourly sync, with no read path to recompute. The correct value survived
# exactly one sync interval.
#
# The guard is SQL inside a large ON CONFLICT clause, which is precisely the kind
# of thing no unit test touches: the JUnit suite never opens a connection, so it
# cannot tell a working CASE expression from one that silently takes the wrong
# arm. This extracts the real statement from the Java source and runs it against
# a real Postgres, in a transaction it rolls back.
#
# Two directions matter and both are checked. A guard that preserved everything
# would be its own bug -- an employer who raises a salary must see it update.
#
#   preserve : re-sync WITHOUT a body must keep the stored derived values
#   update   : re-sync WITH a body must overwrite them
#
# Requires a migrated local database. Run scripts/verify-local.sh, which boots
# the jar (and so runs Flyway) before calling this.
set -uo pipefail

DB="${AIRRAL_DB:-airral_db}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STORE="$ROOT/airral-backend/src/main/java/com/airral/service/ExternalJobPostingStore.java"

if ! pg_isready -q 2>/dev/null; then
  echo "  [SKIP] no local Postgres (brew services start postgresql@14)"
  exit 0
fi
if ! psql -d "$DB" -tAc "SELECT 1 FROM information_schema.columns
     WHERE table_name='external_job_postings' AND column_name='description_text';" 2>/dev/null | grep -q 1; then
  echo "  [FAIL] $DB is not migrated -- run scripts/verify-local.sh first"
  exit 1
fi

SQL=$(python3 - "$STORE" <<'PY'
import io, re, sys
src = io.open(sys.argv[1], encoding='utf-8').read()
m = re.search(r"(INSERT INTO external_job_postings \(.*?LEFT\(COALESCE\(NULLIF\(EXCLUDED\.description_text, ''\), external_job_postings\.description_text\), 2000\)\)\))", src, re.S)
if not m:
    sys.stderr.write("could not extract the upsert SQL -- did the statement change?\n")
    sys.exit(1)
sql = m.group(1)

def render(**kw):
    out = sql
    for k in sorted(kw, key=len, reverse=True):
        out = out.replace(':' + k, kw[k])
    left = set(re.findall(r':[a-zA-Z]+', out))
    if left:
        sys.stderr.write(f"unbound params: {left}\n")
        sys.exit(1)
    return out

base = dict(
    companyId="(SELECT id FROM external_companies LIMIT 1)",
    jobSourceId="(SELECT id FROM external_job_sources LIMIT 1)",
    sourceType="'GREENHOUSE'", sourceName="'Greenhouse'",
    sourceBoardToken="'__guardtest__'", externalJobId="'99001'",
    sourceJobKey="'GREENHOUSE/__guardtest__/99001'",
    title="'Senior Backend Engineer'", department="'Engineering'",
    location="'San Francisco, CA'", workMode="'REMOTE'", employmentType="'Full-time'",
    applyUrl="'https://example.test/a'", jobUrl="'https://example.test/j'",
    applyMode="'EXTERNAL_APPLY'", easyApplyAvailable="false",
    sourceUpdatedAt="now()", postedLabel="'Just updated'",
    matchScore="70", connectionsCount="0",
    tags="ARRAY['Engineering']", tagsText="'Engineering'",
    sourcePayloadHash="'h1'", now="now()", expiresAt="now() + interval '15 days'")

rich = render(**base,
    descriptionText="'Compensation is $180,000 - $230,000. Requires 7+ years. We do not provide visa sponsorship.'",
    salaryLabel="'$180k-$230k'", jobQualityScore="92",
    qualityReasons="ARRAY['Employer salary listed']", totalCompLabel="'Base listed'",
    compensationConfidence="'POSTED_BASE'", sponsorshipLanguage="'NO_SPONSORSHIP'",
    visaConfidenceScore="15", visaReasons="ARRAY['Posting says sponsorship is not available']",
    requiresUsWorkAuthorization="true", contractOrStaffingRisk="false",
    stemOptRisk="true", h1bTransferFit="false", capExemptFit="false",
    experienceYears="7", seniorityLabel="'Senior'")

bare = render(**base, descriptionText="NULL",
    salaryLabel="'Salary not listed'", jobQualityScore="80",
    qualityReasons="ARRAY['Needs salary benchmark']", totalCompLabel="'Benchmark needed'",
    compensationConfidence="'NEEDS_BENCHMARK'", sponsorshipLanguage="'UNKNOWN'",
    visaConfidenceScore="55", visaReasons="ARRAY['Sponsorship not stated']",
    requiresUsWorkAuthorization="NULL", contractOrStaffingRisk="NULL",
    stemOptRisk="NULL", h1bTransferFit="NULL", capExemptFit="NULL",
    experienceYears="NULL", seniorityLabel="NULL")

fresh = render(**base,
    descriptionText="'Updated. Pay is $200,000 - $260,000. We sponsor visas. 9+ years.'",
    salaryLabel="'$200k-$260k'", jobQualityScore="95",
    qualityReasons="ARRAY['Employer salary listed']", totalCompLabel="'Base + extras listed'",
    compensationConfidence="'POSTED_BASE'", sponsorshipLanguage="'SPONSORS'",
    visaConfidenceScore="90", visaReasons="ARRAY['Posting mentions sponsorship']",
    requiresUsWorkAuthorization="false", contractOrStaffingRisk="false",
    stemOptRisk="false", h1bTransferFit="true", capExemptFit="false",
    experienceYears="9", seniorityLabel="'Staff+'")

C = "WHERE source_board_token='__guardtest__'"
print("BEGIN;")
print(f"DELETE FROM external_job_postings {C};")
print(rich + ";")
print(bare + ";")
print(f"""SELECT 'preserve' AS direction,
  CASE WHEN salary_label='$180k-$230k' AND sponsorship_language='NO_SPONSORSHIP'
        AND visa_confidence_score=15 AND experience_years=7 AND seniority_label='Senior'
        AND job_quality_score=92 AND requires_us_work_authorization IS TRUE
        AND stem_opt_risk IS TRUE AND total_comp_label='Base listed'
        AND compensation_confidence='POSTED_BASE'
        AND description_text IS NOT NULL
        AND search_vector @@ to_tsquery('english','sponsorship')
       THEN 'PASS' ELSE 'FAIL' END AS result
  FROM external_job_postings {C};""")
print(f"DELETE FROM external_job_postings {C};")
print(rich + ";")
print(fresh + ";")
print(f"""SELECT 'update' AS direction,
  CASE WHEN salary_label='$200k-$260k' AND sponsorship_language='SPONSORS'
        AND visa_confidence_score=90 AND experience_years=9 AND seniority_label='Staff+'
        AND job_quality_score=95 AND h1b_transfer_fit IS TRUE
        AND total_comp_label='Base + extras listed'
        AND description_text LIKE 'Updated%'
        AND search_vector @@ to_tsquery('english','sponsor')
       THEN 'PASS' ELSE 'FAIL' END AS result
  FROM external_job_postings {C};""")
print("ROLLBACK;")
PY
) || { echo "  [FAIL] could not build the check"; exit 1; }

OUT=$(printf '%s' "$SQL" | psql -d "$DB" -tA -F'|' -v ON_ERROR_STOP=1 2>&1) || {
  echo "  [FAIL] the upsert did not execute:"; printf '%s\n' "$OUT" | sed 's/^/         /'; exit 1; }

RC=0
for d in preserve update; do
  line=$(printf '%s\n' "$OUT" | grep "^$d|" || true)
  case "$line" in
    "$d|PASS") echo "  [ok]   $d — a re-sync $([ "$d" = preserve ] && echo 'without' || echo 'with') a body $([ "$d" = preserve ] && echo 'keeps' || echo 'refreshes') derived values" ;;
    "$d|FAIL") echo "  [FAIL] $d — the ON CONFLICT guard took the wrong arm"; RC=1 ;;
    *)         echo "  [FAIL] $d — no result (got: ${line:-none})"; RC=1 ;;
  esac
done
exit $RC
