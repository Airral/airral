package com.airral.security;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Issues, lists and revokes API keys.
 *
 * <p>Admin-operated to begin with, which is worth more than it looks: there is
 * no public issuance surface to secure on day one, and the tools people
 * actually reach for become clear before key management has to work for
 * thousands of users. Self-service later moves the button, not this class.
 */
@Service
public class ApiKeyIssuanceService {

    /** Enough for an interactive agent session; raised per key when justified. */
    private static final int DEFAULT_RATE_PER_MINUTE = 60;
    private static final int MAX_RATE_PER_MINUTE = 600;

    private final ApiKeyStore apiKeyStore;

    public ApiKeyIssuanceService(ApiKeyStore apiKeyStore) {
        this.apiKeyStore = apiKeyStore;
    }

    /**
     * A newly issued key. {@code rawKey} is the only copy that will ever exist
     * and must reach the user in the response that carries it.
     */
    public record IssuedKey(
            String rawKey,
            String keyId,
            String name,
            String role,
            List<String> scopes,
            String environment,
            int ratePerMinute,
            LocalDateTime expiresAt) {
    }

    public Mono<IssuedKey> issue(
            String forEmail,
            String name,
            List<String> requestedScopes,
            Integer ratePerMinute,
            Integer expiresInDays,
            String environment,
            Long issuedByUserId) {

        if (forEmail == null || forEmail.isBlank()) {
            return Mono.error(new BadRequestException("An email is required to issue a key for"));
        }
        if (name == null || name.isBlank()) {
            // Named on purpose. An unnamed key cannot be told from another in a
            // revocation list, which is exactly when it matters.
            return Mono.error(new BadRequestException(
                    "A name is required, so this key can be recognised later. "
                            + "Something like \"Rahul's Claude Code\"."));
        }

        return apiKeyStore.findUser(forEmail)
                .switchIfEmpty(Mono.error(new NotFoundException(
                        "No active user with email " + forEmail)))
                .flatMap(user -> {
                    List<String> scopes = ApiKeyScopes.grantable(user.role(), requestedScopes);
                    if (scopes.isEmpty()) {
                        // Either the role grants nothing, or every requested
                        // scope was above its ceiling. Both produce a key that
                        // can reach no endpoint, which is not worth issuing.
                        return Mono.error(new BadRequestException(
                                "Role " + user.role() + " has no scopes available"
                                        + (requestedScopes == null || requestedScopes.isEmpty()
                                        ? ". Keys cannot be issued for it."
                                        : " among those requested.")));
                    }

                    String env = (environment == null || environment.isBlank()) ? "live" : environment.trim();
                    ApiKeyFormat.Generated generated = ApiKeyFormat.generate(env);
                    int rate = clampRate(ratePerMinute);
                    LocalDateTime expiresAt = expiresInDays == null || expiresInDays <= 0
                            ? null
                            : LocalDateTime.now().plusDays(expiresInDays);

                    return apiKeyStore.insert(
                                    user.id(),
                                    user.organizationId(),
                                    user.role(),
                                    scopes,
                                    generated.hash(),
                                    generated.keyId(),
                                    env,
                                    name.trim(),
                                    issuedByUserId,
                                    rate,
                                    expiresAt)
                            .thenReturn(new IssuedKey(
                                    generated.raw(),
                                    generated.keyId(),
                                    name.trim(),
                                    user.role(),
                                    scopes,
                                    env,
                                    rate,
                                    expiresAt));
                });
    }

    public Flux<ApiKeyStore.KeySummary> listFor(String email) {
        return apiKeyStore.listForUser(email);
    }

    public Mono<Boolean> revoke(String keyId, String reason) {
        return apiKeyStore.revoke(keyId, reason).map(rows -> rows > 0);
    }

    private int clampRate(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_RATE_PER_MINUTE;
        }
        return Math.min(requested, MAX_RATE_PER_MINUTE);
    }
}
