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
 * Turns a presented API key into a principal shaped like a session's: the same
 * {@link AuthenticationDetails}, the role authority, and the key's scopes.
 *
 * <p>It is not interchangeable with a session, and must not be. Every key also
 * carries {@link ApiKeyScopes#CREDENTIAL_AUTHORITY}, and SecurityConfig refuses
 * that principal anywhere but /mcp. Without the marker, a key holding the ADMIN
 * role passed {@code hasAuthority("ADMIN")} on /api/admin/** and could approve
 * companies, and every other role-gated endpoint was one refactor away from
 * accepting keys with their scopes ignored.
 *
 * <p>Scopes are cut to the role's current ceiling on every request, so narrowing
 * a ceiling in ApiKeyScopes narrows keys that were already issued.
 */
@Component
public class ApiKeyAuthenticationManager implements ReactiveAuthenticationManager {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationManager.class);

    private final ApiKeyStore apiKeyStore;
    private final AiAccessPolicy aiAccessPolicy;

    public ApiKeyAuthenticationManager(ApiKeyStore apiKeyStore, AiAccessPolicy aiAccessPolicy) {
        this.apiKeyStore = apiKeyStore;
        this.aiAccessPolicy = aiAccessPolicy;
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
                // A key someone made for themselves is the paid feature, so it
                // works only while the feature is on for them. An admin's grant
                // is a deliberate bypass and is not checked.
                .filter(key -> !key.selfService() || aiAccessPolicy.includes(key.email()))
                .flatMap(this::enforceRateLimit)
                .map(this::toAuthentication)
                // An unknown, revoked, expired or out-of-date key resolves to
                // empty, which Spring turns into a 401. SecurityContextRepository
                // then works out which it was for the message (see
                // ApiKeyStore.explainMiss for why that is safe to say).
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
        authorities.add(new SimpleGrantedAuthority(ApiKeyScopes.CREDENTIAL_AUTHORITY));
        for (String scope : ApiKeyScopes.effective(key.role(), key.scopes())) {
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
