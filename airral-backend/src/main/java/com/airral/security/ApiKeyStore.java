package com.airral.security;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Reads and writes API keys. Everything here is on the authenticated request
 * path, so it is deliberately small.
 */
@Service
public class ApiKeyStore {

    private final DatabaseClient databaseClient;

    public ApiKeyStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** A key resolved far enough to build a principal from. */
    public record ResolvedKey(
            Long id,
            Long userId,
            String email,
            Long organizationId,
            String organizationTier,
            String role,
            List<String> scopes,
            Boolean isPlatformAdmin,
            int ratePerMinute) {
    }

    /**
     * Resolve a presented key.
     *
     * <p>Joins users for the email, because the principal the JWT path builds is
     * keyed on email and the two must be interchangeable. Reads role and
     * organisation from the key row rather than the user row: those were fixed
     * at issuance, and a credential already sitting in someone's config file
     * should not silently gain or lose reach because their user record changed.
     *
     * <p>Returns empty for a key that is unknown, revoked or expired, so the
     * caller cannot accidentally treat one case as another.
     */
    public Mono<ResolvedKey> resolve(String keyHash) {
        return databaseClient.sql("""
                        SELECT
                            k.id,
                            k.user_id,
                            u.email,
                            k.organization_id,
                            o.tier AS organization_tier,
                            k.role,
                            k.scopes,
                            u.is_platform_admin,
                            k.rate_per_minute
                        FROM api_keys k
                        JOIN users u ON u.id = k.user_id
                        LEFT JOIN organizations o ON o.id = k.organization_id
                        WHERE k.key_hash = :hash
                          AND k.revoked_at IS NULL
                          AND (k.expires_at IS NULL OR k.expires_at > CURRENT_TIMESTAMP)
                          AND u.is_active = true
                        """)
                .bind("hash", keyHash)
                .map((row, meta) -> {
                    String[] scopes = row.get("scopes", String[].class);
                    Boolean platformAdmin = row.get("is_platform_admin", Boolean.class);
                    Integer rate = row.get("rate_per_minute", Integer.class);
                    return new ResolvedKey(
                            row.get("id", Long.class),
                            row.get("user_id", Long.class),
                            row.get("email", String.class),
                            row.get("organization_id", Long.class),
                            row.get("organization_tier", String.class),
                            row.get("role", String.class),
                            scopes == null ? List.of() : List.of(scopes),
                            platformAdmin != null && platformAdmin,
                            rate == null ? 60 : rate);
                })
                .one();
    }

    /**
     * Count this call against the key's per-minute budget and report the running
     * total.
     *
     * <p>One statement, so two instances racing on the same key cannot both read
     * the same count and both decide there is room. The alternative -- a counter
     * in memory -- would give each of the up-to-five Cloud Run instances its own
     * bucket, quietly multiplying every limit by the instance count.
     */
    public Mono<Integer> countCall(Long keyId) {
        return databaseClient.sql("""
                        INSERT INTO api_key_usage (key_id, window_start, calls)
                        VALUES (:keyId, date_trunc('minute', CURRENT_TIMESTAMP), 1)
                        ON CONFLICT (key_id, window_start)
                        DO UPDATE SET calls = api_key_usage.calls + 1
                        RETURNING calls
                        """)
                .bind("keyId", keyId)
                .map((row, meta) -> row.get("calls", Integer.class))
                .one();
    }

    /**
     * Record that a key was used, at most once a minute per key.
     *
     * <p>Writing this on every request would double the writes on the hot path
     * for a column nobody reads in real time. The usage row already tells us
     * whether this is the first call of the window, so the update rides along
     * with it.
     */
    public Mono<Long> touchLastUsed(Long keyId) {
        return databaseClient.sql("""
                        UPDATE api_keys
                        SET last_used_at = CURRENT_TIMESTAMP
                        WHERE id = :keyId
                        """)
                .bind("keyId", keyId)
                .fetch()
                .rowsUpdated();
    }

    /** Nightly hygiene: usage windows are only interesting while they are open. */
    public Mono<Long> purgeUsageBefore(LocalDateTime cutoff) {
        return databaseClient.sql("DELETE FROM api_key_usage WHERE window_start < :cutoff")
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated();
    }
}
