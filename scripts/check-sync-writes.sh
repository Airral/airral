#!/usr/bin/env bash
# Proves the sync's write-side guards hold, against a real Postgres.
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
# The third check covers the disappearance sweep, which is the most destructive
# statement in the pipeline: it retires postings a board has stopped listing. Its
# WHERE clause is the only thing standing between "this job is gone" and "we
# deleted a live employer listing", and again no unit test executes it.
#
#   sweep    : retire only what was not seen this run, never a fresh row,
#              and never an employer's own posting
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

# Anchored to the method: several statements in this file begin "UPDATE
# external_job_postings SET is_active = false", so a pattern that only looks for
# that runs from the first one straight past the end of its own string.
method = src.split("public Mono<Long> deactivateUnseenPostings", 1)
if len(method) < 2:
    sys.stderr.write("deactivateUnseenPostings not found\n")
    sys.exit(1)
sweep = re.search(r"(UPDATE external_job_postings.*?AND last_seen_at < :seenSince)", method[1], re.S)
if not sweep:
    sys.stderr.write("could not extract the sweep SQL -- did deactivateUnseenPostings change?\n")
    sys.exit(1)
sweep_sql = (sweep.group(1)
             .replace(":sourceId", "(SELECT id FROM external_job_sources LIMIT 1)")
             .replace(":seenSince", "now() - interval '1 hour'"))

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
# --- sweep -------------------------------------------------------------
# Three rows on one source: stale (last seen before the cutoff), fresh (seen
# after it), and an employer posting that must be exempt whatever its timestamp.
print(f"DELETE FROM external_job_postings {C};")
for tag, seen, stype in (("stale", "now() - interval '3 hours'", "GREENHOUSE"),
                         ("fresh", "now() - interval '1 minute'", "GREENHOUSE"),
                         ("internal", "now() - interval '3 hours'", "AIRRAL_INTERNAL")):
    print(f"""
        INSERT INTO external_job_postings (
            company_id, job_source_id, source_type, source_name, source_board_token,
            external_job_id, source_job_key, title, apply_mode, easy_apply_available,
            connections_count, tags, sponsorship_language, is_active, last_seen_at,
            expires_at, updated_at, source_updated_at)
        VALUES (
            (SELECT id FROM external_companies LIMIT 1),
            (SELECT id FROM external_job_sources LIMIT 1),
            '{stype}', 'Test', '__guardtest__', 'SWEEP-{tag}', 'SWEEP/{tag}',
            'Sweep {tag}', 'EXTERNAL_APPLY', false, 0, ARRAY[]::text[], 'UNKNOWN',
            true, {seen}, now() + interval '30 days', now(), now());""")

print(sweep_sql + ";")
print(f"""SELECT 'sweep' AS direction,
  CASE WHEN (SELECT is_active FROM external_job_postings {C} AND external_job_id='SWEEP-stale') IS FALSE
        AND (SELECT is_active FROM external_job_postings {C} AND external_job_id='SWEEP-fresh') IS TRUE
        AND (SELECT is_active FROM external_job_postings {C} AND external_job_id='SWEEP-internal') IS TRUE
       THEN 'PASS' ELSE 'FAIL' END AS result;""")

print("ROLLBACK;")
PY
) || { echo "  [FAIL] could not build the check"; exit 1; }

OUT=$(printf '%s' "$SQL" | psql -d "$DB" -tA -F'|' -v ON_ERROR_STOP=1 2>&1) || {
  echo "  [FAIL] the upsert did not execute:"; printf '%s\n' "$OUT" | sed 's/^/         /'; exit 1; }

RC=0
for d in preserve update sweep; do
  line=$(printf '%s\n' "$OUT" | grep "^$d|" || true)
  case "$line" in
    "$d|PASS")
      case "$d" in
        preserve) echo "  [ok]   preserve — a re-sync without a body keeps derived values" ;;
        update)   echo "  [ok]   update — a re-sync with a body refreshes derived values" ;;
        sweep)    echo "  [ok]   sweep — retires only unseen rows, sparing fresh and employer postings" ;;
      esac ;;
    "$d|FAIL") echo "  [FAIL] $d — the guard took the wrong arm"; RC=1 ;;
    *)         echo "  [FAIL] $d — no result (got: ${line:-none})"; RC=1 ;;
  esac
done
exit $RC
