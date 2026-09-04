package com.airral.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.airral.security.ApiKeyFormat;
import com.airral.security.ApiKeyIssuanceService;
import com.airral.security.ApiKeyStore;
import com.airral.security.JwtTokenProvider;

import reactor.core.publisher.Mono;

/**
 * Admin key management.
 *
 * <p>Locked to the ADMIN role in SecurityConfig. Deliberately reached with a
 * session token rather than an API key: issuing credentials from a credential
 * lets a leaked admin key mint replacements for itself, so revocation would
 * stop being final.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyIssuanceService issuanceService;
    private final JwtTokenProvider jwtTokenProvider;

    public ApiKeyAdminController(ApiKeyIssuanceService issuanceService,
                                 JwtTokenProvider jwtTokenProvider) {
        this.issuanceService = issuanceService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public record IssueRequest(
            String email,
            String name,
            List<String> scopes,
            Integer ratePerMinute,
            Integer expiresInDays,
            String environment) {
    }

    /**
     * Issue a key.
     *
     * <p>The response carries the raw key, and it is the only time it will ever
     * appear: only a hash is stored, so there is nothing to show later and
     * nothing for a database leak to expose.
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> issue(
            @RequestBody IssueRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

        Long issuedBy = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));

        return issuanceService.issue(
                        request.email(),
                        request.name(),
                        request.scopes(),
                        request.ratePerMinute(),
                        request.expiresInDays(),
                        request.environment(),
                        issuedBy)
                .map(issued -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("key", issued.rawKey());
                    body.put("keyId", issued.keyId());
                    body.put("name", issued.name());
                    body.put("role", issued.role());
                    body.put("scopes", issued.scopes());
                    body.put("environment", issued.environment());
                    body.put("ratePerMinute", issued.ratePerMinute());
                    body.put("expiresAt", issued.expiresAt());
                    body.put("warning",
                            "This is the only time the key is shown. Store it now; "
                                    + "it cannot be recovered, only replaced.");
                    body.put("connect", "claude mcp add --transport http airral "
                            + "https://mcp.airral.com/mcp --header \"Authorization: Bearer "
                            + issued.rawKey() + "\"");
                    return ResponseEntity.status(HttpStatus.CREATED).body(body);
                });
    }

    /** Keys belonging to one user. Never includes a secret, because none is kept. */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> list(@RequestParam("email") String email) {
        return issuanceService.listFor(email)
                .map(this::describe)
                .collectList()
                .map(ResponseEntity::ok);
    }

    /** Revoke immediately. The next request with this key fails. */
    @DeleteMapping("/{keyId}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(
            @PathVariable("keyId") String keyId,
            @RequestParam(value = "reason", required = false) String reason) {

        return issuanceService.revoke(keyId, reason)
                .map(revoked -> revoked
                        ? ResponseEntity.ok(Map.<String, Object>of(
                                "keyId", keyId, "revoked", true))
                        // Already revoked, or never existed. Reported as 404
                        // rather than a silent success, so a mistyped id is not
                        // mistaken for a key that is now safely off.
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.<String, Object>of(
                                "keyId", keyId,
                                "revoked", false,
                                "message", "No active key with that id")));
    }

    private Map<String, Object> describe(ApiKeyStore.KeySummary key) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("keyId", key.keyId());
        entry.put("name", key.name());
        entry.put("prefix", ApiKeyFormat.displayHint(key.environment(), key.keyId()));
        entry.put("role", key.role());
        entry.put("scopes", key.scopes());
        entry.put("lastUsedAt", key.lastUsedAt());
        entry.put("expiresAt", key.expiresAt());
        entry.put("createdAt", key.createdAt());
        entry.put("active", key.revokedAt() == null);
        entry.put("revokedAt", key.revokedAt());
        return entry;
    }

    private String extractToken(String authHeader) {
        return authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;
    }
}
