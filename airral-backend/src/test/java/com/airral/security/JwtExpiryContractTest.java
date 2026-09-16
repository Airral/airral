package com.airral.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the client is told about a session's length has to be what was stamped.
 *
 * <p>The browser cannot read the expiry itself -- the token is an encrypted JWE
 * -- so {@code AuthResponse.expiresInSeconds} is the only thing standing between
 * the client and its previous behaviour, which was to report every encrypted
 * token as valid forever.
 *
 * <p>{@code getExpirationMillis} reads the same field {@code generateToken}
 * stamps, so today they agree by construction. This test is what keeps that
 * true. If a per-token lifetime is ever added and the accessor keeps answering
 * from config, every browser session silently shortens or lengthens with no
 * error anywhere -- the client would sign people out early, or keep claiming a
 * dead session is live, which is the defect this whole change removes.
 */
class JwtExpiryContractTest {

    private static final long TWO_HOURS_MS = 2 * 60 * 60 * 1000L;

    private JwtTokenProvider providerWithLifetime(long millis) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] { "test" });

        JwtTokenProvider provider = new JwtTokenProvider(environment, new ObjectMapper());
        ReflectionTestUtils.setField(provider, "jwtEncryptionSecret",
                "a-test-secret-long-enough-to-satisfy-the-32-byte-minimum-requirement");
        ReflectionTestUtils.setField(provider, "jwtExpiration", millis);
        ReflectionTestUtils.setField(provider, "jwtIssuer", "airral-api");
        ReflectionTestUtils.setField(provider, "jwtAudience", "airral-web");
        ReflectionTestUtils.setField(provider, "jwtClockSkewSeconds", 30L);
        provider.validateJwtConfiguration();
        return provider;
    }

    @Test
    @DisplayName("the reported lifetime matches the expiry actually stamped on the token")
    void reportedLifetimeMatchesTheStampedExpiry() {
        JwtTokenProvider provider = providerWithLifetime(TWO_HOURS_MS);

        long before = System.currentTimeMillis();
        String token = provider.generateToken(
                7L, "candidate@example.com", "APPLICANT", null, null, false, null, null, 0);
        long after = System.currentTimeMillis();

        Claims claims = provider.getAllClaimsFromToken(token);
        Date stamped = claims.getExpiration();
        assertThat(stamped).as("a token with no expiry cannot be judged by anyone").isNotNull();

        // The client computes expiresAt as its own receipt time plus this value,
        // so the two have to describe the same instant to within the time the
        // mint took -- plus one second, because a JWT exp is a NumericDate in
        // whole seconds and the stamp is truncated down. That truncation means
        // the client's computed instant can sit up to a second after the
        // server's real expiry, which is one of the things the grace period in
        // TokenService absorbs.
        long reportedLifetime = provider.getExpirationMillis();
        assertThat(stamped.getTime())
                .as("the lifetime handed to the browser must be the one stamped on the token")
                .isBetween(before + reportedLifetime - 1000, after + reportedLifetime);
    }

    @Test
    @DisplayName("the reported lifetime follows the configured one, rather than a constant")
    void reportedLifetimeFollowsConfiguration() {
        // Pins that this is read from configuration and not hardcoded anywhere:
        // a client that assumed a fixed 24h would sign live sessions out the day
        // JWT_EXPIRATION_MS was raised, with no 401 to explain it.
        assertThat(providerWithLifetime(TWO_HOURS_MS).getExpirationMillis()).isEqualTo(TWO_HOURS_MS);
        assertThat(providerWithLifetime(86_400_000L).getExpirationMillis()).isEqualTo(86_400_000L);

        JwtTokenProvider shortLived = providerWithLifetime(60_000L);
        Claims claims = shortLived.getAllClaimsFromToken(shortLived.generateToken(
                1L, "a@b.com", "APPLICANT", null, null, false, null, null, 0));
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .as("stamped lifetime tracks configuration too")
                .isBetween(59_000L, 61_000L);
    }
}
