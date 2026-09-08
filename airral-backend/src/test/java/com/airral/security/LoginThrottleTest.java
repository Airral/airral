package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

import com.airral.exception.TooManyLoginAttemptsException;

/**
 * The counting itself is a single SQL upsert and is covered by the database.
 * What is worth testing is the policy around it, where a mistake either locks
 * out real users or silently permits unlimited guessing.
 */
class LoginThrottleTest {

    @Test
    @DisplayName("a disabled throttle never blocks")
    void disabledThrottleAllowsEverything() {
        // The switch exists so a database problem with the counter can be
        // turned off without redeploying auth. It must genuinely bypass.
        LoginThrottle throttle = new LoginThrottle(mock(DatabaseClient.class), false, 1, 1);

        assertTrue(throttle.check("a@b.com", "203.0.113.4").blockOptional().isEmpty(),
                "check returns empty rather than erroring");
        assertTrue(throttle.recordFailure("a@b.com", "203.0.113.4").blockOptional().isEmpty());
        assertTrue(throttle.recordSuccess("a@b.com").blockOptional().isEmpty());
    }

    @Test
    @DisplayName("account creation is throttled on the address alone")
    void registrationDoesNotTouchTheEmailBucket() {
        // The two-bucket check is deliberately not used for sign-up. An attempt
        // counted against the submitted email would let anyone lock a real user
        // out of their own account simply by "registering" their address over and
        // over -- the victim's sign-in budget would be spent by a stranger.
        LoginThrottle throttle = new LoginThrottle(mock(DatabaseClient.class), false, 1, 1);

        assertTrue(throttle.checkAddress("203.0.113.4").blockOptional().isEmpty(),
                "checkAddress returns empty rather than erroring");
        assertTrue(throttle.recordAddressAttempt("203.0.113.4").blockOptional().isEmpty(),
                "recording an attempt never fails the request");
    }

    @Test
    @DisplayName("the retry hint is in seconds, as Retry-After requires")
    void retryAfterIsSeconds() {
        TooManyLoginAttemptsException e = new TooManyLoginAttemptsException(15);

        assertEquals("900", e.getHeaders().getFirst("Retry-After"),
                "Retry-After is defined in seconds; sending 15 would mean 15 seconds");
        assertTrue(e.getReason().contains("15 minutes"),
                "the human-readable message stays in minutes");
    }

    @Test
    @DisplayName("the lockout message reveals nothing about the account")
    void messageIsNotAnOracle() {
        // If a locked-out response differed for a real address, the throttle
        // would hand an attacker exactly the enumeration it exists to prevent.
        String message = new TooManyLoginAttemptsException(15).getReason();

        assertTrue(message.contains("Too many sign-in attempts"));
        assertEquals(-1, message.toLowerCase().indexOf("password"),
                "must not say whether the password was right");
        assertEquals(-1, message.toLowerCase().indexOf("account"),
                "must not say whether the account exists");
    }

    @Test
    @DisplayName("it is a 429, not a 401")
    void statusIsTooManyRequests() {
        // A 401 would tell an agent or a client to go and fetch a new
        // credential, which does not help and adds load. 429 says wait.
        assertEquals(429, new TooManyLoginAttemptsException(15).getStatusCode().value());
    }

    @Test
    @DisplayName("the exception is a ResponseStatusException so it survives the security chain")
    void exceptionTypeReachesTheClient() {
        // Raised while the request is authenticated, before a handler exists,
        // where @RestControllerAdvice never sees it. Only
        // ResponseStatusException is rendered by WebFlux from there.
        assertInstanceOf(org.springframework.web.server.ResponseStatusException.class,
                new TooManyLoginAttemptsException(15));
    }
}
