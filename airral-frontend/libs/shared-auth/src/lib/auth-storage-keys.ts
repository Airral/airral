/**
 * Storage keys for the session, kept free of Angular imports so they can be
 * read and written before the application bootstraps.
 */
export const AUTH_TOKEN_KEY = 'auth_token';
export const AUTH_USER_KEY = 'current_user';

/**
 * Where the session's end time lives.
 *
 * <p>A third key rather than a field on the user object, because the handoff
 * writer and the pre-bootstrap restore both touch these keys directly and a
 * sidecar is the only shape all of them can write without importing Angular.
 */
export const AUTH_SESSION_EXPIRY_KEY = 'auth_session_expiry';
