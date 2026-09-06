package com.airral.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * The current session generation for a user, cached briefly.
 *
 * <p>Session tokens are self-verifying, which is what made them unrevocable:
 * with nothing stored there was nothing to delete, so a stolen token stayed
 * good for its full 24 hours. Comparing a claim against a stored version fixes
 * that, but it also turns every authenticated request into a database read --
 * on a db-f1-micro with 25 connections that is not a small change.
 *
 * <p>So it is cached for a few seconds. The cost is that revocation is not
 * instant: a token can survive up to one TTL after being revoked, per instance.
 * That is the deliberate trade. Going from "valid for a day, no exceptions" to
 * "valid for a few more seconds" is nearly all of the benefit, and doing it
 * without a per-request read is what makes it affordable here.
 *
 * <p>Cleared on write, so a revocation issued through this process takes effect
 * at once and only other instances wait out the TTL.
 *
 * <p>Deliberately not a cache library. One map with timestamps needs no new
 * dependency, and this application's dependency graph is pinned in ways that
 * make additions genuinely risky.
 */
@Service
public class TokenVersionCache {

    private static final Logger log = LoggerFactory.getLogger(TokenVersionCache.class);

    private record Entry(int version, Instant readAt) {
    }

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private final DatabaseClient databaseClient;
    private final Duration ttl;

    public TokenVersionCache(
            DatabaseClient databaseClient,
            @Value("${airral.auth.token-version-cache-seconds:30}") int ttlSeconds) {
        this.databaseClient = databaseClient;
        this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
    }

    public Mono<Integer> current(Long userId) {
        if (userId == null) {
            return Mono.just(0);
        }

        Entry cached = cache.get(userId);
        if (cached != null && Instant.now().isBefore(cached.readAt().plus(ttl))) {
            return Mono.just(cached.version());
        }

        return databaseClient.sql("SELECT token_version FROM users WHERE id = :id")
                .bind("id", userId)
                .map((row, meta) -> {
                    Integer version = row.get("token_version", Integer.class);
                    return version == null ? 0 : version;
                })
                .one()
                .defaultIfEmpty(0)
                .doOnNext(version -> cache.put(userId, new Entry(version, Instant.now())))
                // Failing open. A database problem must not sign everybody out:
                // the token was already cryptographically valid, and refusing it
                // trades a revocation window for a total outage.
                .onErrorResume(error -> {
                    log.error("Could not read token_version for {}, accepting token: {}",
                            userId, error.getMessage());
                    return Mono.just(cached == null ? 0 : cached.version());
                });
    }

    /**
     * Invalidate every outstanding token for this user.
     *
     * <p>Used on password change and on an explicit sign-out-everywhere. The
     * local cache entry is dropped immediately so this instance stops honouring
     * old tokens at once.
     */
    public Mono<Integer> revokeAll(Long userId) {
        return databaseClient.sql("""
                        UPDATE users
                        SET token_version = token_version + 1
                        WHERE id = :id
                        RETURNING token_version
                        """)
                .bind("id", userId)
                .map((row, meta) -> row.get("token_version", Integer.class))
                .one()
                .doOnNext(version -> {
                    cache.remove(userId);
                    log.warn("All sessions revoked for user {} (token_version now {})", userId, version);
                });
    }
}
