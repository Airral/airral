package com.airral.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A presented API key that will not authenticate, with the reason.
 *
 * <p>Extends {@link ResponseStatusException} rather than {@link ApiException}
 * for the same reason {@link ApiKeyRateLimitExceededException} does: this is
 * raised while the security context loads, before a handler exists, where
 * {@code GlobalExceptionHandler} never sees it.
 *
 * <p>Says which of expired, revoked or unknown it was. Ordinarily an
 * authentication error should not explain itself, but the secret half of a key
 * is 256 bits of CSPRNG output: an attacker cannot produce a candidate worth
 * testing, so there is no enumeration to protect against. Meanwhile a person
 * whose key stopped working overnight would otherwise be debugging a bare 401
 * with no way to tell an expiry from a typo.
 */
public class ApiKeyRejectedException extends ResponseStatusException {

    public ApiKeyRejectedException(String reason) {
        super(HttpStatus.UNAUTHORIZED, reason);
    }
}
