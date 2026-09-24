#!/usr/bin/env python3
"""
The admin Launch page's numbers, against a running local API and database.

The SQL behind /api/admin/analytics/launch is the part no unit test can prove,
so this signs up two applicants -- one on a real-looking domain, one on
example.com -- sends an "Apply now" click from the first one's session, and
checks that the funnel counts the real one, ties the click to them, and leaves
the test address out. It also checks that only an admin can read the page.

Exit codes: 0 all passed, 1 a check failed.
"""
import json, random, subprocess, sys, urllib.error, urllib.request

API = "http://localhost:8080/api"
failures = 0


def call(path, body=None, token=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{API}{path}", data=json.dumps(body).encode() if body is not None else None,
                                 headers=headers, method=method or ("POST" if body is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else {}
        except ValueError:
            return e.code, {}


def check(ok, message):
    global failures
    print(("  [ok]   " if ok else "  [FAIL] ") + message)
    if not ok:
        failures += 1


def psql(sql):
    subprocess.run(["psql", "-d", "airral_db", "-qtAc", sql], check=True, capture_output=True)


n = random.randint(100000, 999999)
real = f"launch-{n}@gate-launch.dev"
test = f"launch-{n}@example.com"
admin = f"launch-admin-{n}@example.com"
try:
    st, _ = call("/admin/analytics/launch")
    check(st == 401, "the launch numbers need a session")

    st, reg_admin = call("/auth/register", {"email": admin, "password": "Original1pass"})
    st, forbidden = call("/admin/analytics/launch", token=reg_admin.get("token"))
    check(st == 403, "an applicant cannot read them")

    psql(f"UPDATE users SET role = 'ADMIN' WHERE email = '{admin}'")
    st, login = call("/auth/login", {"email": admin, "password": "Original1pass"})
    admin_token = login.get("token")

    st, before = call("/admin/analytics/launch?days=1", token=admin_token)
    check(st == 200 and "funnel" in before, "an admin can read them")

    st, reg_real = call("/auth/register", {"email": real, "password": "Original1pass", "firstName": "Gate", "lastName": "Launch"})
    call("/auth/register", {"email": test, "password": "Original1pass"})
    st, _ = call("/events", {"event": "apply_click", "path": "/jobs", "app": "applicant"}, token=reg_real.get("token"))
    check(st == 204, "a signed-in Apply click is accepted")

    st, after = call("/admin/analytics/launch?days=1", token=admin_token)
    fb, fa = before["funnel"], after["funnel"]
    check(fa["signedUp"] == fb["signedUp"] + 1, "the real sign-up is counted, and only that one")
    check(fa["testAccountsHidden"] >= fb["testAccountsHidden"] + 1, "the example.com sign-up is left out")
    check(fa["clickedApply"] == fb["clickedApply"] + 1, "the Apply click is tied to the person who made it")
    check(after["traffic"]["applyClicks"] == before["traffic"]["applyClicks"] + 1, "the click is in the traffic count")
    person = next((a for a in after["recentApplicants"] if a["email"] == real), None)
    check(person is not None and person["applyClicks"] == 1 and person["name"] == "Gate Launch"
          and not any(a["email"] == test for a in after["recentApplicants"]),
          "the newest-applicants list shows the real person with their click, and not the test one")
    check(len(after["daily"]) == 1 and after["daily"][0]["signups"] >= 1, "today's row counts the sign-up")

    st, week = call("/admin/analytics/launch?days=7", token=admin_token)
    check(st == 200 and len(week["daily"]) == 7, "a week has seven days, empty ones included")
finally:
    psql(f"DELETE FROM users WHERE email IN ('{real}', '{test}', '{admin}')")

sys.exit(1 if failures else 0)
