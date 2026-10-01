package com.airral.exception;

import com.airral.security.ApiKeyStore;

/**
 * Wording for a refused API key, aimed at the person who has to act on it.
 *
 * <p>Ordinarily an authentication failure should not explain itself. Here it
 * can: the secret half of a key is 256 bits of CSPRNG output, so an attacker
 * has no candidate worth testing and learning that some key once existed is
 * worth nothing. Meanwhile a holder whose key stopped working overnight would
 * otherwise face a bare 401 with no way to tell an expiry from a bad paste.
 *
 * <p>Not an exception any more -- the reason is rendered by the authentication
 * entry point, which is the one place a 401 body is written. Kept in the
 * exception package because that is where the message lived when it was thrown.
 */
public final class ApiKeyRejectedException {

    private static final String MAKE_A_NEW_ONE =
            "Make a new one in AIRRAL under Connect an AI assistant, or ask the person who gave it to you.";

    private ApiKeyRejectedException() {
    }

    public static String describe(ApiKeyStore.MissReason reason) {
        if (reason == null) {
            return "This API key could not be verified.";
        }
        return switch (reason) {
            case EXPIRED -> "This API key has expired. " + MAKE_A_NEW_ONE;
            case REVOKED -> "This API key has been revoked and cannot be reactivated. " + MAKE_A_NEW_ONE;
            case USER_INACTIVE -> "The account this API key belongs to is no longer active.";
            case ACCOUNT_CHANGED -> "This API key's account has changed role or company since the key was made, "
                    + "so the key no longer works. " + MAKE_A_NEW_ONE;
            case COMPANY_NOT_APPROVED -> "This API key belongs to a company AIRRAL has not approved, "
                    + "or that is no longer active.";
            case SESSION_ENDED -> "This API key was made from a sign-in that has since ended. " + MAKE_A_NEW_ONE;
            case FEATURE_NOT_INCLUDED -> "Connecting an AI assistant is no longer on this account, "
                    + "so its keys have stopped working.";
            case UNKNOWN -> "This API key is not recognised. Check it was copied in full, "
                    + "including the airral_ak_ prefix.";
        };
    }
}
