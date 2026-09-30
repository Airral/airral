package com.airral.security;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.ForbiddenException;
import com.airral.exception.NotFoundException;
import com.airral.exception.UnauthorizedException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Issues, lists and revokes API keys.
 *
 * <p>Two ways in. An admin issues on someone's behalf ({@link #issue}), and a
 * person whose account has the feature makes their own ({@link #issueForSelf}).
 * Self-service is deliberately narrower: read-only scopes, a fixed rate, a
 * 90-day life, at most {@link #MAX_SELF_SERVICE_KEYS} keys, and only while
 * {@link AiAccessPolicy} says yes.
 */
@Service
public class ApiKeyIssuanceService {

    /** Enough for an interactive agent session; raised per key when justified. */
    private static final int DEFAULT_RATE_PER_MINUTE = 60;
    private static final int MAX_RATE_PER_MINUTE = 600;

    /** Enough for a laptop, a desktop and one to rotate into. */
    public static final int MAX_SELF_SERVICE_KEYS = 3;
    /** A self-made key ends on its own; the person makes a new one. */
    public static final int SELF_SERVICE_DAYS = 90;
    private static final int MAX_NAME_LENGTH = 80;

    private final ApiKeyStore apiKeyStore;
    private final AiAccessPolicy aiAccessPolicy;
    private final TransactionalOperator transactions;

    @Autowired
    public ApiKeyIssuanceService(ApiKeyStore apiKeyStore, AiAccessPolicy aiAccessPolicy,
                                 ReactiveTransactionManager transactionManager) {
        this(apiKeyStore, aiAccessPolicy, TransactionalOperator.create(transactionManager));
    }

    ApiKeyIssuanceService(ApiKeyStore apiKeyStore, AiAccessPolicy aiAccessPolicy, TransactionalOperator transactions) {
        this.apiKeyStore = apiKeyStore;
        this.aiAccessPolicy = aiAccessPolicy;
        this.transactions = transactions;
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

    /**
     * Issue a key on someone's behalf.
     *
     * <p>Deliberately does not consult {@link AiAccessPolicy}. An admin needs
     * to hand a key to a prospect mid-demo, or to a paying customer whose
     * billing has not landed yet, and a grant that cannot bypass the paywall is
     * a support problem waiting to happen. Who granted it is recorded in
     * issued_by.
     */
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
        String nameProblem = nameProblem(name);
        if (nameProblem != null) {
            return Mono.error(new BadRequestException(nameProblem));
        }
        if (environment != null && !environment.isBlank()
                && !List.of("live", "test").contains(environment.trim())) {
            return Mono.error(new BadRequestException("Environment must be live or test"));
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

    /**
     * A person makes a key for themselves.
     *
     * <p>Everything is decided here from the user's row as it is now: whether
     * the feature is on for the account, the scopes (read-only, by role), the
     * rate and the expiry. Nothing about the key comes from the request except
     * its name.
     *
     * <p>{@code sessionVersion} is the token version of the session asking. The
     * key records it and is stored only while it is current, so a session that
     * was revoked a moment ago -- still honoured by another instance's cache --
     * cannot leave a working key behind. The count and the insert run under a
     * per-user lock, so requests at once cannot get past the cap together.
     */
    public Mono<IssuedKey> issueForSelf(Long userId, String name, int sessionVersion) {
        String nameProblem = nameProblem(name);
        if (nameProblem != null) {
            return Mono.error(new BadRequestException(nameProblem));
        }
        return apiKeyStore.findSelfServiceUser(userId)
                .switchIfEmpty(Mono.error(new NotFoundException("Account not found")))
                .flatMap(user -> {
                    AiAccessPolicy.Decision decision = aiAccessPolicy.decide(user);
                    if (!decision.available()) {
                        return Mono.error(new ForbiddenException(decision.reason(), decision.message()));
                    }
                    Mono<IssuedKey> issue = apiKeyStore.lockForIssuance(user.id())
                            .then(apiKeyStore.countUsable(user.id()))
                            .flatMap(count -> {
                        if (count >= MAX_SELF_SERVICE_KEYS) {
                            return Mono.error(new ConflictException("You already have " + MAX_SELF_SERVICE_KEYS
                                    + " keys. Revoke one you no longer use to make another."));
                        }
                        List<String> scopes = ApiKeyScopes.selfService(user.role());
                        ApiKeyFormat.Generated generated = ApiKeyFormat.generate("live");
                        LocalDateTime expiresAt = LocalDateTime.now().plusDays(SELF_SERVICE_DAYS);
                        return apiKeyStore.insertSelfService(
                                        user.id(),
                                        user.organizationId(),
                                        user.role(),
                                        scopes,
                                        generated.hash(),
                                        generated.keyId(),
                                        name.trim(),
                                        DEFAULT_RATE_PER_MINUTE,
                                        expiresAt,
                                        sessionVersion)
                                .switchIfEmpty(Mono.error(new UnauthorizedException(
                                        "Your sign-in has ended. Sign in again to make a key.")))
                                .thenReturn(new IssuedKey(
                                        generated.raw(),
                                        generated.keyId(),
                                        name.trim(),
                                        user.role(),
                                        scopes,
                                        "live",
                                        DEFAULT_RATE_PER_MINUTE,
                                        expiresAt));
                            });
                    return transactions.transactional(issue);
                });
    }

    /** Whether the feature is on for this person, read fresh. */
    public Mono<AiAccessPolicy.Decision> accessFor(Long userId) {
        return apiKeyStore.findSelfServiceUser(userId)
                .map(aiAccessPolicy::decide)
                .defaultIfEmpty(aiAccessPolicy.decide(null));
    }

    public Flux<ApiKeyStore.KeySummary> listOwn(Long userId) {
        return apiKeyStore.listActiveForUser(userId);
    }

    /** Revoke one of your own keys. False when there was no such key of yours. */
    public Mono<Boolean> revokeOwn(Long userId, String keyId) {
        return apiKeyStore.revokeOwned(keyId, userId, "Revoked by its owner").map(rows -> rows > 0);
    }

    /**
     * Named on purpose. An unnamed key cannot be told from another in a
     * revocation list, which is exactly when it matters.
     */
    static String nameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "A name is required, so this key can be recognised later. "
                    + "Something like \"My laptop\".";
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return "Keep the name to " + MAX_NAME_LENGTH + " characters.";
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            return "The name can only contain ordinary characters.";
        }
        return null;
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
