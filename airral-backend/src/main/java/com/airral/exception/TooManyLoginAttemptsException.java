package com.airral.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Too many failed sign-ins for this account or from this address.
 *
 * <p>Says the same thing whether or not the account exists, and whether or not
 * the password was right. Confirming either would turn the throttle into an
 * oracle: an attacker who learns "that address is real" from a lockout message
 * has been handed something the rate limit was meant to withhold.
 */
public class TooManyLoginAttemptsException extends ResponseStatusException {

    private final long retryAfterMinutes;

    public TooManyLoginAttemptsException(long retryAfterMinutes) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Too many sign-in attempts. Try again in " + retryAfterMinutes + " minutes.");
        this.retryAfterMinutes = retryAfterMinutes;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterMinutes * 60));
        return headers;
    }
}
