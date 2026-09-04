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

    private ApiKeyRejectedException() {
    }

    public static String describe(ApiKeyStore.MissReason reason) {
        if (reason == null) {
            return "This API key could not be verified.";
        }
        return switch (reason) {
            case EXPIRED -> "This API key has expired. Ask an AIRRAL admin to issue a new one.";
            case REVOKED -> "This API key has been revoked and cannot be reactivated. "
                    + "Ask an AIRRAL admin to issue a new one.";
            case USER_INACTIVE -> "The account this API key belongs to is no longer active.";
            case UNKNOWN -> "This API key is not recognised. Check it was copied in full, "
                    + "including the airral_ak_ prefix.";
        };
    }
}
