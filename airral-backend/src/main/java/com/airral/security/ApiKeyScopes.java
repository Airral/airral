package com.airral.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What each role's key is allowed to ask for.
 *
 * <p>Scopes are derived from role at issuance rather than chosen per key, and a
 * key may hold fewer than its role allows but never more. That ordering is the
 * point: if the issuing screen is ever wrong, or someone crafts a request to
 * it, the worst case is a key that can do less than expected. A flow that let
 * scopes be supplied freely could mint an employer key for an applicant.
 *
 * <p>Spring Security is told about scopes as authorities prefixed
 * {@code SCOPE_}, alongside the role authority the JWT path already grants, so
 * an endpoint can require either and existing role rules keep working.
 */
public final class ApiKeyScopes {

    public static final String AUTHORITY_PREFIX = "SCOPE_";

    /**
     * Carried by every principal built from an API key, and by no session.
     *
     * <p>Scopes alone cannot mark a key: a key holding no scope would look like
     * a session. SecurityConfig uses this to keep keys on /mcp, and the key
     * management endpoints use it to refuse a key minting more keys.
     */
    public static final String CREDENTIAL_AUTHORITY = "CREDENTIAL_API_KEY";

    // ── applicant ──
    public static final String JOBS_READ = "jobs:read";
    public static final String PROFILE_READ = "profile:read";
    public static final String SAVED_WRITE = "saved:write";
    public static final String APPLICATIONS_READ = "applications:read";

    // ── employer ──
    public static final String PIPELINE_READ = "pipeline:read";
    public static final String PIPELINE_WRITE = "pipeline:write";
    public static final String JOBS_WRITE = "jobs:write";

    // ── admin ──
    public static final String ADMIN_KEYS = "admin:keys";

    private static final Set<String> APPLICANT = Set.of(
            JOBS_READ, PROFILE_READ, SAVED_WRITE, APPLICATIONS_READ);

    // Each employer ceiling matches what that role can do in the HR portal: HR
    // runs jobs and the pipeline, a manager works the pipeline of the jobs they
    // hire for, and an employee only interviews. A tool that shipped later could
    // otherwise be used by every employer key already issued.
    private static final Set<String> HR_MANAGER = Set.of(
            JOBS_READ, PIPELINE_READ, PIPELINE_WRITE, JOBS_WRITE);

    private static final Set<String> MANAGER = Set.of(
            JOBS_READ, PIPELINE_READ);

    private static final Set<String> EMPLOYEE = Set.of(
            JOBS_READ);

    private static final Set<String> ADMIN = Set.of(
            JOBS_READ, PIPELINE_READ, PIPELINE_WRITE, JOBS_WRITE, ADMIN_KEYS);

    private ApiKeyScopes() {
    }

    /**
     * The most a key issued to this role may hold.
     *
     * <p>Note what is absent: there is no {@code sourcing:read}. Letting an
     * employer search candidates who never applied to them is a different
     * product from letting them see their own applicants, with a consent model
     * attached, and it is not something to enable by adding a string here.
     */
    public static Set<String> maximumFor(String role) {
        String normalized = role == null ? "" : role.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPLICANT" -> APPLICANT;
            case "HR_MANAGER" -> HR_MANAGER;
            case "MANAGER" -> MANAGER;
            case "EMPLOYEE" -> EMPLOYEE;
            case "ADMIN" -> ADMIN;
            // An unrecognised role gets nothing rather than a default. A key
            // that authenticates but can reach no endpoint is a visible bug;
            // one that quietly inherits applicant scope is not.
            default -> Set.of();
        };
    }

    /**
     * Narrow a requested scope list to what the role actually permits.
     * Anything unrecognised or above the role's ceiling is dropped, not refused,
     * so a stale client asking for a scope that no longer exists still works.
     */
    public static List<String> grantable(String role, List<String> requested) {
        Set<String> ceiling = maximumFor(role);
        if (requested == null || requested.isEmpty()) {
            return List.copyOf(ceiling);
        }
        return requested.stream()
                .filter(ceiling::contains)
                .distinct()
                .toList();
    }

    /**
     * What a key someone makes for themselves holds: read-only, and only what a
     * tool exists for today. Everything else in the role's ceiling waits for its
     * tool, and then for a new key the person agrees to.
     *
     * <p>Admins get nothing. An admin credential sitting in a config file is the
     * one key worth stealing, so admins do not self-issue.
     */
    public static List<String> selfService(String role) {
        String normalized = role == null ? "" : role.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "APPLICANT", "EMPLOYEE" -> List.of(JOBS_READ);
            case "HR_MANAGER", "MANAGER" -> List.of(JOBS_READ, PIPELINE_READ);
            default -> List.of();
        };
    }

    /**
     * The scopes a key may use right now: what it was issued with, cut to its
     * role's current ceiling. Checked on every request, so narrowing a ceiling
     * here narrows every key already issued.
     */
    public static List<String> effective(String role, List<String> held) {
        Set<String> ceiling = maximumFor(role);
        return held == null ? List.of() : held.stream().filter(ceiling::contains).distinct().toList();
    }

    public static String authority(String scope) {
        return AUTHORITY_PREFIX + scope;
    }
}
