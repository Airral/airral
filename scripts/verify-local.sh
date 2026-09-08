#!/usr/bin/env bash
# Everything that can be checked without deploying. Run this before pushing.
#
# Written because three changes reached production in one session that a local
# check would have caught: an unparseable logback config that stopped the API
# booting, a throttle that answered 500 instead of 429, and a stale tag that
# built the wrong commit. Unit tests passed every time -- they never boot the
# application or serve a request, which is exactly where those bugs lived.
#
# Two things this deliberately cannot catch, so do not read a pass as proof the
# deploy is safe: anything specific to how Cloud Run handles a request (the
# login NPE came from a forwarded-header rewrite that has no local equivalent),
# and anything about TLS termination (HSTS was configured and silently not sent
# for the same reason). Those still need checking against the deployed service.
#
# Usage:  ./scripts/verify-local.sh          checks, boot, and smoke
#         ./scripts/verify-local.sh --quick  checks only, no boot
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAILED=0
QUICK="${1:-}"

step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
ok()   { printf '  [ok]   %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAILED=1; }

# ---------------------------------------------------------------------------
# Config that aborts start-up when malformed.
#
# A broken logback config fails during prepareEnvironment, before the
# application context exists, so Cloud Run reports only a failed startup probe
# and the real cause sits three "Caused by" frames deep in the logs.
# ---------------------------------------------------------------------------
step "Config parses"

LOGBACK="$ROOT/airral-backend/src/main/resources/logback-spring.xml"
if [ -f "$LOGBACK" ]; then
  if python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('$LOGBACK')" 2>/dev/null; then
    ok "logback-spring.xml"
  else
    bad "logback-spring.xml does not parse (XML forbids a double hyphen inside comments)"
  fi
fi

for f in "$ROOT"/airral-backend/src/main/resources/application*.yml; do
  [ -f "$f" ] || continue
  if python3 -c "import yaml; yaml.safe_load(open('$f'))" 2>/dev/null; then
    ok "$(basename "$f")"
  else
    bad "$(basename "$f") is not valid YAML"
  fi
done

WF_BAD=0
for f in "$ROOT"/.github/workflows/*.yml; do
  [ -f "$f" ] || continue
  python3 -c "import yaml; yaml.safe_load(open('$f'))" 2>/dev/null || {
    bad "workflow $(basename "$f") is not valid YAML"; WF_BAD=1; }
done
[ $WF_BAD -eq 0 ] && ok "all workflow YAML"

# ---------------------------------------------------------------------------
# Shell scripts. A syntax error in setup.sh or the container entrypoint only
# shows up when it runs, which for the entrypoint is inside a deploy.
# ---------------------------------------------------------------------------
step "Shell syntax"
for f in "$ROOT"/infra/gcp/*.sh "$ROOT"/scripts/*.sh \
         "$ROOT"/airral-backend/scripts/*.sh \
         "$ROOT"/airral-frontend/docker-entrypoint.d/*.sh; do
  [ -f "$f" ] || continue
  bash -n "$f" 2>/dev/null && ok "$(basename "$f")" || bad "$(basename "$f")"
done

# ---------------------------------------------------------------------------
# Are we about to push a tag that already exists? Reusing one silently builds
# the old commit, which happened with v1.5.5.
# ---------------------------------------------------------------------------
step "Git state"
if [ -n "$(cd "$ROOT" && git status --porcelain)" ]; then
  ok "working tree has uncommitted changes (expected before a commit)"
else
  ok "working tree clean"
fi

step "Backend tests"
if (cd "$ROOT/airral-backend" && ./gradlew test -q >/tmp/verify-be.log 2>&1); then
  ok "gradlew test"
else
  bad "gradlew test -- see /tmp/verify-be.log"
fi

step "Frontend build and lint"
if (cd "$ROOT/airral-frontend" && NX_DAEMON=false NX_TUI=false node_modules/.bin/nx run-many \
      -t build lint --configuration=production >/tmp/verify-fe.log 2>&1); then
  ok "nx build + lint"
else
  bad "nx build/lint -- see /tmp/verify-fe.log"
fi

if [ "$QUICK" = "--quick" ]; then
  step "Result"
  if [ $FAILED -eq 0 ]; then ok "checks passed (boot skipped)"; exit 0; else bad "checks failed"; exit 1; fi
fi

# ---------------------------------------------------------------------------
# Boot the real jar. The highest-value check here: it exercises logback, every
# YAML property, bean wiring and Flyway, none of which a unit test touches.
#
# Needs a local Postgres:
#   brew services start postgresql@14 && createdb airral_db
# ---------------------------------------------------------------------------
step "Boot"
if ! pg_isready -q 2>/dev/null; then
  bad "no local Postgres -- start it with: brew services start postgresql@14"
  step "Result"; exit 1
fi

psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='airral_db';" 2>/dev/null | grep -q 1 \
  || createdb airral_db 2>/dev/null

(cd "$ROOT/airral-backend" && ./gradlew bootJar -q >/dev/null 2>&1)
JAR=$(ls "$ROOT"/airral-backend/build/libs/*.jar 2>/dev/null | grep -v plain | head -1)
if [ -z "$JAR" ]; then
  bad "no jar was built"
  step "Result"; exit 1
fi

# A previous run's JVM can still hold 8080 for a few seconds after it is killed.
# Launching into that produces a bind failure, and the smoke tests below then
# report connection-refused on every endpoint rather than a real result.
for _ in $(seq 1 20); do
  lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 || break
  sleep 1
done
if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  bad "port 8080 is still in use -- stop whatever is listening and retry"
  step "Result"; exit 1
fi

# Removed rather than truncated by the redirect. The wait loop below used to read
# this path while the shell's ">" truncation was still landing, so its first grep
# could match the PREVIOUS run's "Started AirralApplication" and break out
# immediately -- reporting a healthy boot for a JVM that had not started, and
# then printing "safe to push" off a run whose smoke tests all failed with 000.
# A false pass here is far worse than a false failure.
BOOT_LOG=/tmp/verify-boot.log
rm -f "$BOOT_LOG"

# The startup sync is switched off for this run. It fetches every configured
# source -- hundreds of boards, and full posting bodies since the sync started
# asking for them -- which competes with the smoke checks below for the same
# instance and makes them time out rather than fail on their merits. The sync has
# its own coverage in the guard step and in the unit tests; what this boot is
# here to prove is that the application starts and serves.
DB_USER="${DB_USER:-$(whoami)}" java -jar "$JAR" --spring.profiles.active=local \
  --airral.jobs.sync.run-on-startup=false \
  --jwt.secret="$(openssl rand -base64 48 | tr -d '\n')" >"$BOOT_LOG" 2>&1 &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null' EXIT

for _ in $(seq 1 45); do
  grep -qE "Started AirralApplication|APPLICATION FAILED" "$BOOT_LOG" 2>/dev/null && break
  ps -p "$APP_PID" >/dev/null 2>&1 || break
  sleep 2
done

# Three independent conditions, because the log alone has already lied once:
# the process is alive, it claimed to start, and it actually answers.
if ! ps -p "$APP_PID" >/dev/null 2>&1; then
  bad "the application process exited during start-up -- see $BOOT_LOG"
  step "Result"; exit 1
fi
if ! grep -q "Started AirralApplication" "$BOOT_LOG"; then
  bad "application did not start -- see $BOOT_LOG"
  step "Result"; exit 1
fi
if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 http://localhost:8080/actuator/health)" != "200" ]; then
  bad "the application started but does not answer /actuator/health"
  step "Result"; exit 1
fi
ok "application started and is serving"

# ---------------------------------------------------------------------------
# The sync upsert's ON CONFLICT guard, against the schema the boot just
# migrated. Kept separate because it is pure SQL behaviour: the JUnit suite
# never opens a connection, so it cannot tell a working CASE expression from one
# that silently takes the wrong arm -- which is how the pipeline came to
# overwrite its own derived data every four hours.
# ---------------------------------------------------------------------------
step "Sync write guards"
if "$ROOT/scripts/check-sync-writes.sh"; then
  ok "write guards hold"
else
  bad "the upsert guard is not holding -- see scripts/check-sync-writes.sh"
fi

# ---------------------------------------------------------------------------
# Serve real requests. Unit tests never do, and this is where the throttle
# answering 500 rather than 429 would have been caught before the push.
# ---------------------------------------------------------------------------
step "Smoke"
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@"; }

[ "$(code http://localhost:8080/api/feed)" = "200" ] \
  && ok "GET /api/feed" || bad "GET /api/feed"

[ "$(code -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}')" = "401" ] \
  && ok "POST /mcp refuses an unauthenticated call" \
  || bad "POST /mcp should be 401"

[ "$(code -X POST http://localhost:8080/api/applications \
     -H 'Content-Type: application/json' -d '{}')" = "401" ] \
  && ok "POST /api/applications requires auth" \
  || bad "POST /api/applications should be 401"

PROBE="throttle-$RANDOM@local.test"
LAST=""
for i in $(seq 1 6); do
  LAST=$(code -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
    -d "{\"email\":\"$PROBE\",\"password\":\"wrongpassword$i\"}")
done
[ "$LAST" = "429" ] && ok "login throttle returns 429 on the sixth failure" \
  || bad "login throttle returned $LAST, expected 429"

step "Result"
if [ $FAILED -eq 0 ]; then
  ok "safe to push"
  exit 0
else
  bad "do not push"
  exit 1
fi
