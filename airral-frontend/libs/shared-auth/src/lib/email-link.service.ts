import { Injectable } from '@angular/core';
import { FIREBASE_WEB_CONFIG } from '@airral/shared-utils';

/** Where the address typed on this device is kept until its link comes back. */
const PENDING_EMAIL_KEY = 'airral.emailLink.pendingEmail';

/**
 * Proves someone owns an email address, using a link Firebase emails.
 *
 * <p>This is the only thing AIRRAL uses Firebase for. It asks Firebase to email a
 * one-time link; when the person follows it, Firebase hands this page an ID token
 * signed by Google saying the address was confirmed, and that token goes to
 * AIRRAL's own API, which checks it and updates AIRRAL's own account. No password
 * ever reaches Firebase, and the Firebase-side record the link creates is deleted
 * again once AIRRAL has what it needs.
 *
 * <p>The Firebase SDK is loaded on demand, so pages that never send or receive a
 * link do not pay for it.
 */
@Injectable({ providedIn: 'root' })
export class EmailLinkService {
  private authPromise: Promise<import('firebase/auth').Auth> | null = null;

  /** Email a verification link. `path` is where on this portal the link lands. */
  async sendLink(email: string, path: '/verify-email' | '/reset-password', origin?: string): Promise<void> {
    const address = email.trim().toLowerCase();
    const { sendSignInLinkToEmail } = await import('firebase/auth');
    const auth = await this.auth();
    await sendSignInLinkToEmail(auth, address, {
      url: `${origin ?? window.location.origin}${path}`,
      handleCodeInApp: true,
    });
    this.rememberEmail(address);
  }

  /** Whether the current URL is a link Firebase sent. */
  async isEmailLink(url: string = window.location.href): Promise<boolean> {
    const { isSignInWithEmailLink } = await import('firebase/auth');
    return isSignInWithEmailLink(await this.auth(), url);
  }

  /**
   * The address this device typed before sending the link, if it did.
   *
   * <p>Firebase requires the address again to complete a link -- a check that the
   * link was not forwarded to someone else. On the device that asked for it the
   * page already knows it; on another device the person is asked to type it.
   */
  rememberedEmail(): string | null {
    try {
      return localStorage.getItem(PENDING_EMAIL_KEY);
    } catch {
      return null;
    }
  }

  /**
   * Redeem the link for a Google-signed ID token proving the address.
   *
   * <p>Call {@link discard} once AIRRAL's API has accepted the token.
   */
  async redeem(email: string, url: string = window.location.href): Promise<string> {
    const { signInWithEmailLink } = await import('firebase/auth');
    const credential = await signInWithEmailLink(await this.auth(), email.trim().toLowerCase(), url);
    return credential.user.getIdToken();
  }

  /**
   * Remove the Firebase-side record the link created and sign out of Firebase.
   *
   * <p>Firebase is a messenger here, not an account store, so it should not keep
   * a list of AIRRAL's users. Best effort: failing to delete it leaves an email
   * address in Firebase and nothing else -- no password, no role, nothing AIRRAL
   * reads.
   */
  async discard(): Promise<void> {
    try {
      const { signOut } = await import('firebase/auth');
      const auth = await this.auth();
      if (auth.currentUser) {
        await auth.currentUser.delete().catch(() => undefined);
      }
      await signOut(auth).catch(() => undefined);
    } finally {
      try {
        localStorage.removeItem(PENDING_EMAIL_KEY);
      } catch {
        /* storage unavailable: nothing to clear */
      }
    }
  }

  private rememberEmail(email: string): void {
    try {
      localStorage.setItem(PENDING_EMAIL_KEY, email);
    } catch {
      /* private mode: the landing page will ask for the address instead */
    }
  }

  private auth(): Promise<import('firebase/auth').Auth> {
    if (!this.authPromise) {
      this.authPromise = (async () => {
        const { initializeApp, getApps } = await import('firebase/app');
        const { getAuth, inMemoryPersistence, setPersistence } = await import('firebase/auth');
        const app = getApps().length ? getApps()[0] : initializeApp(FIREBASE_WEB_CONFIG);
        const auth = getAuth(app);
        // Never keep a Firebase session: the only thing wanted from Firebase is
        // one token, used once. AIRRAL's own session is the real one.
        await setPersistence(auth, inMemoryPersistence);
        return auth;
      })();
    }
    return this.authPromise;
  }
}

/** Human wording for the Firebase error codes a person can actually hit. */
export function emailLinkErrorMessage(error: unknown): string {
  const code = (error as { code?: string } | null)?.code ?? '';
  switch (code) {
    case 'auth/invalid-email':
      return 'Enter a valid email address.';
    case 'auth/invalid-action-code':
    case 'auth/expired-action-code':
      return 'This link has expired or was already used. Request a new one.';
    case 'auth/too-many-requests':
    case 'auth/quota-exceeded':
      return 'Too many attempts. Please wait a few minutes and try again.';
    case 'auth/network-request-failed':
      return 'We could not reach the server. Check your connection and try again.';
    default:
      return 'Something went wrong. Please try again.';
  }
}

/**
 * Whether an API error is the "verify your email first" refusal (403
 * EMAIL_NOT_VERIFIED), which deserves its own message rather than a generic
 * failure -- a person told "upload failed, try a smaller file" will fiddle with
 * the file, not check their inbox.
 */
export function isEmailNotVerifiedError(error: unknown): boolean {
  const e = error as { status?: number; error?: { error?: string } } | null;
  return e?.status === 403 && e?.error?.error === 'EMAIL_NOT_VERIFIED';
}
