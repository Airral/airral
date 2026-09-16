/**
 * When the session ends, and how confident we are about it.
 *
 * <p>The backend issues an encrypted JWE, so the browser cannot read the
 * token's own `exp`. Before this existed, `TokenService.isTokenExpired`
 * returned `false` for every encrypted token unconditionally -- so anyone who
 * had ever signed in appeared signed in forever, while the server expired them
 * after 24 hours. `isAuthenticated()` was a claim the client had no way to
 * back, and it cost the email unsubscribe page: the shell fired an
 * authenticated request on a page a signed-out reader was using, the request
 * 401'd, and the reader was logged out and redirected mid-task.
 *
 * <p>`source` is carried so the difference between knowing and assuming stays
 * visible all the way to the words shown to a person. A session we cannot
 * vouch for is not reported as expired -- it is reported as unconfirmed.
 */
export type SessionExpirySource = 'server' | 'assumed';

export interface SessionExpiry {
  /** Epoch milliseconds, anchored to this browser's clock at receipt. */
  expiresAt: number;
  source: SessionExpirySource;
}

/**
 * What we can say about the stored session.
 *
 * <p>Deliberately four states, and deliberately no `valid`. `unexpired` means
 * *not known to have expired*: the server also rejects a token inside its `exp`
 * when the user's `tokenVersion` has moved on, which the client cannot see. So
 * the 401 handler in the interceptor stays load-bearing forever, and no caller
 * should treat `unexpired` as permission.
 */
export type SessionStatus = 'none' | 'unexpired' | 'expired' | 'unverifiable';

/** Why a stored session was discarded, for the words shown to the user. */
export type SessionEndReason = 'expired' | 'unverifiable';

/**
 * How long past the computed end we still treat a session as live.
 *
 * <p>Sixty seconds, covering three separate sources of slop that all push the
 * same way: a JWT `exp` is truncated to whole seconds so the client's computed
 * instant can sit up to a second late; the round trip between the server
 * stamping the token and the browser anchoring it adds the request latency; and
 * the server itself already allows `jwt.clock-skew-seconds` (30s by default) on
 * top of the stamp.
 *
 * <p>Erring this way is the safe direction. A minute of over-optimism costs one
 * request that comes back 401 and is handled; a minute of over-caution signs
 * somebody out who was still perfectly able to work.
 */
export const SESSION_EXPIRY_GRACE_MS = 60_000;

/**
 * The lifetime assumed for a session handed over without one.
 *
 * <p>Only reachable from a cross-portal handoff fragment built by a bundle
 * older than this change, which happens on every deploy while caches drain.
 * Refusing those would break cross-portal sign-in for the length of the
 * rollout, and the admin portal has no other door. So the session is accepted
 * and labelled `assumed`, never `server`.
 */
export const ASSUMED_SESSION_LIFETIME_MS = 24 * 60 * 60 * 1000;

/**
 * The expiry a login response describes, anchored to now.
 *
 * <p>Relative seconds on the wire, absolute instant in storage. That ordering
 * is what makes a wrong browser clock harmless: the duration is measured by the
 * server and merely counted by the client, so a clock that is hours out still
 * yields the right end time relative to itself. An absolute server timestamp
 * compared against that same bad clock would be wrong by the whole error.
 */
export function sessionExpiryFromResponse(
  expiresInSeconds: number | null | undefined,
  receivedAt: number = Date.now()
): SessionExpiry {
  if (typeof expiresInSeconds !== 'number' || !Number.isFinite(expiresInSeconds) || expiresInSeconds <= 0) {
    // A response with no usable lifetime comes from a backend older than this
    // change. Labelled, not trusted, and not refused -- refusing would make
    // signing in impossible during a staged deploy.
    return { expiresAt: receivedAt + ASSUMED_SESSION_LIFETIME_MS, source: 'assumed' };
  }

  // Clamped so an absurd value cannot mint a session that outlives any server
  // token, which would put the client back to claiming validity it cannot back.
  const lifetimeMs = Math.min(expiresInSeconds * 1000, ASSUMED_SESSION_LIFETIME_MS * 30);
  return { expiresAt: receivedAt + lifetimeMs, source: 'server' };
}

/**
 * What to tell someone who was sent to sign in without choosing to.
 *
 * <p>Lives beside the states it describes so the wording cannot drift from the
 * reason that produced it, and so all four portals say the same thing.
 *
 * <p>The two sentences are deliberately different. "Expired" is a fact we can
 * state. "Could not confirm" is the honest reading for a session with no usable
 * end time -- which is every session stored before this shipped -- and
 * presenting that as expired would be inventing a fact in the one place this
 * change exists to stop inventing them.
 */
export function noticeForSessionEndReason(reason: string | null | undefined): string {
  if (reason === 'expired') {
    return 'Your session ran out, so you were signed out. Please sign in again.';
  }
  if (reason === 'unverifiable') {
    return 'We could not confirm your session, so we signed you out. Please sign in again.';
  }
  return '';
}

/**
 * Where the reason a session was discarded waits for the login page.
 *
 * <p>Deliberately storage rather than a field on AuthService, and deliberately
 * not one-shot. The guard redirects with a full page navigation, so an instance
 * field cannot survive to be read on the other side -- and the guard can run
 * more than once for a single navigation, so the first read consuming the value
 * left the second read with nothing and the second redirect won the race,
 * landing the user on a bare form with no explanation. Observed exactly that.
 *
 * <p>sessionStorage rather than localStorage: this is about one navigation, and
 * it should not outlive the tab or leak into another one.
 */
const SESSION_END_REASON_KEY = 'auth_session_end_reason';

export function rememberSessionEndReason(reason: SessionEndReason): void {
  try {
    globalThis.sessionStorage?.setItem(SESSION_END_REASON_KEY, reason);
  } catch {
    // Private browsing, or storage disabled. The URL parameter the guard adds
    // is the other half of this, so the notice still appears.
  }
}

/** Non-destructive on purpose -- see the note above about repeated guard runs. */
export function readSessionEndReason(): SessionEndReason | null {
  try {
    const value = globalThis.sessionStorage?.getItem(SESSION_END_REASON_KEY);
    return value === 'expired' || value === 'unverifiable' ? value : null;
  } catch {
    return null;
  }
}

export function clearSessionEndReason(): void {
  try {
    globalThis.sessionStorage?.removeItem(SESSION_END_REASON_KEY);
  } catch {
    // Nothing to clear if storage is unavailable.
  }
}
