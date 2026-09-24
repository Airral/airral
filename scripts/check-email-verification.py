#!/usr/bin/env python3
"""
Email verification and password reset, end to end, against the real Firebase
project and a running local API.

No inbox is involved. Firebase's admin API can generate the exact link it would
email without sending it (returnOobLink); redeeming that link the way the browser
SDK does yields a genuine Google-signed ID token. So this proves the part no unit
test can: that AIRRAL's backend accepts real Firebase tokens for this project, and
what it does with them.

The links a person actually receives are sent by the API itself, and only to
accounts that exist. The local profile turns delivery off and logs each link
instead, so with AIRRAL_API_LOG pointing at the running API's log this also
redeems the very links the API produced -- sign-up, resend and forgot-password
-- and checks that forgot-password looks the same for a stranger.

Exit codes: 0 all passed, 1 a check failed, 2 skipped (no gcloud credentials or
no network) -- the caller treats 2 as a skip, not a failure.
"""
import json, os, random, re, subprocess, sys, time, urllib.error, urllib.parse, urllib.request

PROJECT = "airral-a0e81"
API_KEY = "AIzaSyBEVR_Zk-T_XDGPKSa_IIeJB-XubcMC21w"   # public web key, referrer-restricted
REFERER = "http://localhost:4201/"
API = "http://localhost:8080/api"
API_LOG = os.environ.get("AIRRAL_API_LOG", "")
failures = 0


def call(url, body=None, headers=None, method="POST"):
    req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **(headers or {})}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read() or b"{}")
        except ValueError:
            return e.code, {}


def check(ok, message):
    global failures
    print(("  [ok]   " if ok else "  [FAIL] ") + message)
    if not ok:
        failures += 1


try:
    oauth = subprocess.run(["gcloud", "auth", "print-access-token"], capture_output=True, text=True, timeout=30).stdout.strip()
except Exception:
    oauth = ""
if not oauth:
    print("  [SKIP] no gcloud credentials, so no real Firebase link can be generated")
    sys.exit(2)
ADMIN = {"Authorization": f"Bearer {oauth}", "x-goog-user-project": PROJECT}
uids = []


def redeem(email, link):
    """Follow a link the way the browser SDK does, returning the ID token."""
    oob = urllib.parse.parse_qs(urllib.parse.urlparse(link).query)["oobCode"][0]
    st, d = call(f"https://identitytoolkit.googleapis.com/v1/accounts:signInWithEmailLink?key={API_KEY}",
                 {"email": email, "oobCode": oob}, {"Referer": REFERER})
    assert st == 200, (st, d)
    uids.append(d.get("localId"))
    return d["idToken"]


def link_token(email, path):
    st, d = call(f"https://identitytoolkit.googleapis.com/v1/projects/{PROJECT}/accounts:sendOobCode",
                 {"requestType": "EMAIL_SIGNIN", "email": email, "returnOobLink": True,
                  "continueUrl": f"http://localhost:4201{path}", "canHandleCodeInApp": True}, ADMIN)
    if st != 200:
        print(f"  [SKIP] Firebase would not generate a link ({st}); check gcloud access to {PROJECT}")
        sys.exit(2)
    return redeem(email, d["oobLink"])


def log_size():
    return os.path.getsize(API_LOG) if API_LOG else 0


def logged_links(since, purpose, user_id, wait=10):
    """Links the API logged for this user since the given log offset."""
    pattern = re.compile(rf"{purpose} link for user {user_id}: (\S+)")
    deadline = time.time() + wait
    while True:
        with open(API_LOG, encoding="utf-8", errors="replace") as f:
            f.seek(since)
            found = pattern.findall(f.read())
        if found or time.time() > deadline:
            return found
        time.sleep(0.5)


try:
    n = random.randint(100000, 999999)
    email = f"gate-{n}@example.com"
    mark = log_size()
    st, reg = call(f"{API}/auth/register", {"email": email, "password": "Original1pass"})
    check(st == 201 and reg.get("emailVerified") is False, "a password sign-up starts unverified")
    session = {"Authorization": f"Bearer {reg.get('token', '')}"}

    st, d = call(f"{API}/candidate/notifications/preferences", {"jobAlertEnabled": True}, session, "PUT")
    check(st == 403 and d.get("error") == "EMAIL_NOT_VERIFIED", "an unverified account cannot turn email on")
    st, _ = call(f"{API}/candidate/notifications/preferences", {"jobAlertEnabled": False}, session, "PUT")
    check(st == 200, "an unverified account can always turn email off")

    if API_LOG:
        links = logged_links(mark, "verify", reg.get("userId"))
        check(len(links) == 1 and links[0] != "null", "sign-up has the API send one verification link")
        token = redeem(email, links[0]) if links else link_token(email, "/verify-email")
    else:
        print("  [SKIP] AIRRAL_API_LOG not set: using an admin-generated link instead of the API's")
        token = link_token(email, "/verify-email")
    st, d = call(f"{API}/auth/verify-email", {"idToken": token})
    check(st == 200 and d.get("verified") is True, "a real Firebase link verifies the address")
    st, d = call(f"{API}/auth/me", None, session, "GET")
    check(st == 200 and d.get("emailVerified") is True, "the same session sees it without signing in again")
    st, _ = call(f"{API}/candidate/notifications/preferences", {"jobAlertEnabled": True}, session, "PUT")
    check(st == 200, "a verified account can turn email on")

    st, d = call(f"{API}/auth/send-verification", {}, session)
    check(st == 200 and d.get("alreadyVerified") is True, "a verified account is not sent another link")

    # Forgot-password: one answer, after one wait, whether or not there is an account.
    mark = log_size()
    started = time.time()
    st_known, known = call(f"{API}/auth/forgot-password", {"email": email.upper()})
    known_secs = time.time() - started
    started = time.time()
    st_stranger, stranger = call(f"{API}/auth/forgot-password", {"email": f"nobody-{n}@example.com"})
    stranger_secs = time.time() - started
    check(st_known == st_stranger == 202 and known == stranger,
          "forgot-password answers a stranger exactly as it answers an account")
    check(min(known_secs, stranger_secs) >= 2.4, f"both take the full wait ({known_secs:.1f}s, {stranger_secs:.1f}s)")
    if API_LOG:
        links = logged_links(mark, "reset", reg.get("userId"))
        check(len(links) == 1, "the account is sent a reset link")
        with open(API_LOG, encoding="utf-8", errors="replace") as f:
            f.seek(mark)
            every_reset = re.findall(r"reset link for user", f.read())
        check(len(every_reset) == 1, "the stranger is sent nothing")
        reset_token = redeem(email, links[0]) if links else link_token(email, "/reset-password")
    else:
        reset_token = link_token(email, "/reset-password")
    st, _ = call(f"{API}/auth/reset-password", {"idToken": reset_token, "password": "Changed1pass"})
    check(st == 200, "a real Firebase link resets the password")
    st, _ = call(f"{API}/auth/login", {"email": email, "password": "Original1pass"})
    check(st == 401, "the old password stops working")
    st, _ = call(f"{API}/auth/login", {"email": email, "password": "Changed1pass"})
    check(st == 200, "the new password signs in")
    st, _ = call(f"{API}/auth/me", None, session, "GET")
    check(st == 401, "sessions from before the reset are signed out")

    good = link_token(email, "/verify-email")
    st, _ = call(f"{API}/auth/verify-email", {"idToken": good[:-6] + "AAAAAA"})
    check(st == 400, "a tampered token is refused")

    # Employers: a job reaches candidates only once the company is verified.
    def catalogue_has(title):
        st, d = call(f"{API}/candidate/jobs/recommended/page?q={urllib.parse.quote(title)}&limit=20", None, None, "GET")
        return any(j.get("title") == title for j in d.get("jobs", []))

    owner = f"owner@gate{n}-corp.com"
    st, reg = call(f"{API}/auth/register", {"email": owner, "password": "Original1pass", "firstName": "G", "lastName": "O",
                                            "companyName": f"Gate Corp {n}", "organizationTier": "ENTERPRISE"})
    check(st == 201 and reg.get("organizationTier") == "QUICK_HIRE", "sign-up ignores a tier the caller asked for")
    hr = {"Authorization": f"Bearer {reg.get('token', '')}"}
    title = f"Gate Warehouse Lead {n}"
    call(f"{API}/jobs", {"title": title, "description": "Real work.", "location": "Austin, TX", "status": "OPEN"}, hr)
    check(not catalogue_has(title), "an unverified employer's OPEN job is not shown to candidates")
    call(f"{API}/auth/verify-email", {"idToken": link_token(owner, "/verify-email")})
    check(catalogue_has(title), "proving an address on the company's own domain publishes it")

    free = f"gate.{n}@gmail.com"
    st, reg2 = call(f"{API}/auth/register", {"email": free, "password": "Original1pass", "firstName": "G", "lastName": "F",
                                             "companyName": "Stripe"})
    title2 = f"Gate Remote Data Entry {n}"
    call(f"{API}/jobs", {"title": title2, "description": "Real work.", "location": "Remote", "status": "OPEN"},
         {"Authorization": f"Bearer {reg2.get('token', '')}"})
    free_session = {"Authorization": f"Bearer {reg2.get('token', '')}"}
    # Sign-up already used one of the three links an account gets per 15 minutes.
    sends = [call(f"{API}/auth/send-verification", {}, free_session) for _ in range(3)]
    check([st for st, _ in sends] == [202, 202, 429] and sends[2][1].get("error") == "EMAIL_LINK_RATE_LIMITED",
          "resend works, then stops at three links per account")
    call(f"{API}/auth/verify-email", {"idToken": link_token(free, "/verify-email")})
    check(not catalogue_has(title2), "a verified free-mail employer still waits for review")
finally:
    for uid in filter(None, uids):
        call(f"https://identitytoolkit.googleapis.com/v1/projects/{PROJECT}/accounts:delete", {"localId": uid}, ADMIN)

sys.exit(1 if failures else 0)
