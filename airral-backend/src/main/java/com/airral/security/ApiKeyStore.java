package com.airral.security;

import java.time.LocalDateTime;
import java.util.List;

import io.r2dbc.spi.Parameters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
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

    /** A key as it may be shown after issuance: identity, never the secret. */
    public record KeySummary(
            String keyId,
            String name,
            String role,
            List<String> scopes,
            String environment,
            LocalDateTime lastUsedAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt,
            LocalDateTime createdAt) {
    }

    /**
     * Store a new key. The caller holds the only copy of the raw value and must
     * return it to the user in this one response.
     */
    public Mono<Long> insert(
            Long userId,
            Long organizationId,
            String role,
            List<String> scopes,
            String keyHash,
            String keyId,
            String environment,
            String name,
            Long issuedBy,
            int ratePerMinute,
            LocalDateTime expiresAt) {

        return databaseClient.sql("""
                        INSERT INTO api_keys (
                            user_id, organization_id, role, scopes,
                            key_hash, key_id, environment, name, issued_by,
                            rate_per_minute, expires_at
                        ) VALUES (
                            :userId, :organizationId, :role, :scopes,
                            :keyHash, :keyId, :environment, :name, :issuedBy,
                            :ratePerMinute, :expiresAt
                        )
                        RETURNING id
                        """)
                .bind("userId", userId)
                .bind("organizationId", organizationId == null ? Parameters.in(Long.class) : Parameters.in(organizationId))
                .bind("role", role)
                .bind("scopes", scopes.toArray(new String[0]))
                .bind("keyHash", keyHash)
                .bind("keyId", keyId)
                .bind("environment", environment)
                .bind("name", name)
                .bind("issuedBy", issuedBy == null ? Parameters.in(Long.class) : Parameters.in(issuedBy))
                .bind("ratePerMinute", ratePerMinute)
                .bind("expiresAt", expiresAt == null ? Parameters.in(LocalDateTime.class) : Parameters.in(expiresAt))
                .map((row, meta) -> row.get("id", Long.class))
                .one();
    }

    /** Every key belonging to one user, including revoked ones. */
    public Flux<KeySummary> listForUser(String email) {
        return databaseClient.sql("""
                        SELECT k.key_id, k.name, k.role, k.scopes, k.environment,
                               k.last_used_at, k.expires_at, k.revoked_at, k.created_at
                        FROM api_keys k
                        JOIN users u ON u.id = k.user_id
                        WHERE lower(u.email) = lower(:email)
                        ORDER BY k.created_at DESC
                        """)
                .bind("email", email)
                .map((row, meta) -> {
                    String[] scopes = row.get("scopes", String[].class);
                    return new KeySummary(
                            row.get("key_id", String.class),
                            row.get("name", String.class),
                            row.get("role", String.class),
                            scopes == null ? List.of() : List.of(scopes),
                            row.get("environment", String.class),
                            row.get("last_used_at", LocalDateTime.class),
                            row.get("expires_at", LocalDateTime.class),
                            row.get("revoked_at", LocalDateTime.class),
                            row.get("created_at", LocalDateTime.class));
                })
                .all();
    }

    /**
     * Revoke by public id, which is the only identifier anyone has after
     * issuance. Already-revoked keys are left alone so the original reason and
     * timestamp survive a second attempt.
     */
    public Mono<Long> revoke(String keyId, String reason) {
        return databaseClient.sql("""
                        UPDATE api_keys
                        SET revoked_at = CURRENT_TIMESTAMP,
                            revoked_reason = :reason
                        WHERE key_id = :keyId AND revoked_at IS NULL
                        """)
                .bind("keyId", keyId)
                .bind("reason", reason == null ? "revoked" : reason)
                .fetch()
                .rowsUpdated();
    }

    /** The user a key is being issued for, and the role that fixes its scopes. */
    public Mono<UserForKey> findUser(String email) {
        return databaseClient.sql("""
                        SELECT id, email, organization_id, role
                        FROM users
                        WHERE lower(email) = lower(:email) AND is_active = true
                        """)
                .bind("email", email)
                .map((row, meta) -> new UserForKey(
                        row.get("id", Long.class),
                        row.get("email", String.class),
                        row.get("organization_id", Long.class),
                        row.get("role", String.class)))
                .one();
    }

    public record UserForKey(Long id, String email, Long organizationId, String role) {
    }

    /** Why a key that was presented did not resolve. */
    public enum MissReason { UNKNOWN, REVOKED, EXPIRED, USER_INACTIVE }

    /**
     * Explain a failed lookup, for the error message only.
     *
     * <p>Deliberately a second query rather than relaxing the filters in
     * {@link #resolve}. That one is on every authenticated request and rides
     * the partial index on {@code (key_hash) WHERE revoked_at IS NULL}; widening
     * it to report status would give up the index for every successful call to
     * improve the message on a failing one. This runs only after a miss, so the
     * cost lands on the path that is already an error.
     *
     * <p>Distinguishing "expired" from "unknown" does leak that a given key
     * once existed. Against a 256-bit random secret that oracle is worthless --
     * an attacker cannot produce a candidate to test -- while a holder of a real
     * key that quietly stopped working has no way to tell an expiry from a typo.
     */
    public Mono<MissReason> explainMiss(String keyHash) {
        return databaseClient.sql("""
                        SELECT k.revoked_at, k.expires_at, u.is_active
                        FROM api_keys k
                        JOIN users u ON u.id = k.user_id
                        WHERE k.key_hash = :hash
                        """)
                .bind("hash", keyHash)
                .map((row, meta) -> {
                    if (row.get("revoked_at", LocalDateTime.class) != null) {
                        return MissReason.REVOKED;
                    }
                    LocalDateTime expiresAt = row.get("expires_at", LocalDateTime.class);
                    if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
                        return MissReason.EXPIRED;
                    }
                    Boolean active = row.get("is_active", Boolean.class);
                    if (active != null && !active) {
                        return MissReason.USER_INACTIVE;
                    }
                    return MissReason.UNKNOWN;
                })
                .one()
                .defaultIfEmpty(MissReason.UNKNOWN);
    }
}
