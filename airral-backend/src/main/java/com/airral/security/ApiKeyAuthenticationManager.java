package com.airral.security;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.airral.exception.ApiKeyRateLimitExceededException;
import com.airral.security.AuthenticationManager.AuthenticationDetails;

import reactor.core.publisher.Mono;

/**
 * Turns a presented API key into the same principal a JWT would have produced.
 *
 * <p>"The same" is the whole design. The authorities and
 * {@link AuthenticationDetails} built here are indistinguishable from the JWT
 * path's, so every existing controller, guard and authorization rule keeps
 * working untouched and cannot tell which credential arrived. The alternative --
 * a second, key-only API -- would give every endpoint two authorization paths
 * to keep in agreement, and they drift.
 *
 * <p>Scope authorities are added on top of the role authority, so a key can be
 * held to a narrower reach than the role alone would imply while role-based
 * rules continue to apply.
 */
@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationManager.class);

    private final ApiKeyStore apiKeyStore;

    public ApiKeyAuthenticationManager(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String presented = authentication.getCredentials().toString();
        if (!ApiKeyFormat.looksLikeApiKey(presented)) {
            return Mono.empty();
        }

        // Only the hash is ever compared, and only the hash is ever stored.
        String hash = ApiKeyFormat.sha256(presented);

        return apiKeyStore.resolve(hash)
                .flatMap(this::enforceRateLimit)
                .map(this::toAuthentication)
                // An unknown, revoked or expired key resolves to empty, which
                // Spring turns into a 401. Nothing about which of those it was
                // is reported back: that distinction is useful to an attacker
                // enumerating keys and to nobody else.
                //
                // A throttled key is not an authentication failure and must not
                // be flattened into one -- it has to reach the caller as a 429,
                // so it is re-raised rather than swallowed here.
                .onErrorResume(error -> {
                    if (error instanceof ApiKeyRateLimitExceededException) {
                        return Mono.error(error);
                    }
                    log.warn("API key authentication failed: {}", error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<ApiKeyStore.ResolvedKey> enforceRateLimit(ApiKeyStore.ResolvedKey key) {
        return apiKeyStore.countCall(key.id())
                .flatMap(calls -> {
                    if (calls != null && calls > key.ratePerMinute()) {
                        // Deliberately an error rather than an empty result: a
                        // throttled caller has a valid key and needs to be told
                        // to slow down, not told their credential is bad.
                        return Mono.error(new ApiKeyRateLimitExceededException(key.ratePerMinute()));
                    }
                    // First call of this minute, so this is also the cheapest
                    // moment to record that the key is alive.
                    if (calls != null && calls == 1) {
                        return apiKeyStore.touchLastUsed(key.id()).thenReturn(key);
                    }
                    return Mono.just(key);
                });
    }

    private Authentication toAuthentication(ApiKeyStore.ResolvedKey key) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(key.role()));
        for (String scope : key.scopes()) {
            authorities.add(new SimpleGrantedAuthority(ApiKeyScopes.authority(scope)));
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                key.email(),
                null,
                authorities);

        auth.setDetails(new AuthenticationDetails(
                key.userId(),
                key.organizationId(),
                key.organizationTier(),
                key.role(),
                key.isPlatformAdmin()));

        return auth;
    }
}
