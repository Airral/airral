package com.airral.controller;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.airral.exception.ForbiddenException;
import com.airral.exception.UnauthorizedException;
import com.airral.security.ApiKeyFormat;
import com.airral.security.ApiKeyIssuanceService;
import com.airral.security.ApiKeyReachFilter;
import com.airral.security.ApiKeyScopes;
import com.airral.security.ApiKeyStore;
import com.airral.security.AuthenticationManager.AuthenticationDetails;
import com.airral.security.JwtTokenProvider;

import reactor.core.publisher.Mono;

/**
 * A person's own API keys, for connecting an AI assistant to AIRRAL.
 *
 * <p>Reached with a session only. ApiKeyReachFilter already keeps keys off
 * /api, and this checks again, because a key that could mint keys could never
 * really be revoked. Who is asking comes from the verified principal, never
 * from the request.
 */
@RestController
@RequestMapping("/api/account/api-keys")
public class AccountApiKeyController {

    private final ApiKeyIssuanceService issuanceService;
    private final JwtTokenProvider jwtTokenProvider;
    private final String mcpUrl;

    public AccountApiKeyController(ApiKeyIssuanceService issuanceService,
                                   JwtTokenProvider jwtTokenProvider,
                                   @Value("${airral.mcp.public-url:https://mcp.airral.com/mcp}") String mcpUrl) {
        this.issuanceService = issuanceService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.mcpUrl = mcpUrl;
    }

    public record CreateRequest(String name) {
    }

    /**
     * Whether the feature is on for this account, and the keys it has. The
     * portals link the page when {@code included} is true or keys remain.
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> overview() {
        return currentUser().flatMap(caller -> issuanceService.accessFor(caller.getUserId())
                .flatMap(decision -> issuanceService.listOwn(caller.getUserId())
                        .map(this::describe)
                        .collectList()
                        .map(keys -> {
                            Map<String, Object> body = new LinkedHashMap<>();
                            body.put("included", decision.included());
                            body.put("available", decision.available());
                            body.put("reason", decision.reason());
                            body.put("message", decision.message());
                            body.put("mcpUrl", mcpUrl);
                            body.put("maxKeys", ApiKeyIssuanceService.MAX_SELF_SERVICE_KEYS);
                            body.put("keyLifetimeDays", ApiKeyIssuanceService.SELF_SERVICE_DAYS);
                            // What a key made here would hold, so the page can
                            // say what the assistant will be able to do.
                            body.put("selfServiceScopes", ApiKeyScopes.selfService(caller.getRole()));
                            // Always listed, feature or not: a key that still
                            // works must never be one its owner cannot see or
                            // switch off.
                            body.put("keys", keys);
                            return ResponseEntity.ok(body);
                        })));
    }

    /**
     * Make a key. The response carries the raw key, the only time it will ever
     * appear: only a hash is stored.
     */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody(required = false) CreateRequest request,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return currentUser()
                .flatMap(caller -> issuanceService.issueForSelf(caller.getUserId(),
                        request == null ? null : request.name(),
                        jwtTokenProvider.getTokenVersionFromToken(authHeader.substring("Bearer ".length()))))
                .map(issued -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("key", issued.rawKey());
                    body.put("keyId", issued.keyId());
                    body.put("name", issued.name());
                    body.put("scopes", issued.scopes());
                    body.put("ratePerMinute", issued.ratePerMinute());
                    body.put("expiresAt", withOffset(issued.expiresAt()));
                    body.put("mcpUrl", mcpUrl);
                    return ResponseEntity.status(HttpStatus.CREATED).body(body);
                });
    }

    /** Revoke one of your keys. The next request made with it fails. */
    @DeleteMapping("/{keyId}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable("keyId") String keyId) {
        return currentUser()
                .flatMap(caller -> issuanceService.revokeOwn(caller.getUserId(), keyId))
                .map(revoked -> revoked
                        ? ResponseEntity.ok(Map.<String, Object>of("keyId", keyId, "revoked", true))
                        // Someone else's key and a mistyped id look the same.
                        : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.<String, Object>of(
                                "keyId", keyId,
                                "revoked", false,
                                "message", "You have no active key with that id.")));
    }

    private Mono<AuthenticationDetails> currentUser() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(context -> context.getAuthentication())
                .switchIfEmpty(Mono.error(new UnauthorizedException("Sign in to manage your keys")))
                .flatMap(this::sessionUser);
    }

    private Mono<AuthenticationDetails> sessionUser(Authentication authentication) {
        if (ApiKeyReachFilter.isApiKey(authentication)) {
            return Mono.error(new ForbiddenException("API_KEY_NOT_ALLOWED",
                    "Keys are managed from a signed-in session, not with a key."));
        }
        if (authentication.getDetails() instanceof AuthenticationDetails details && details.getUserId() != null) {
            return Mono.just(details);
        }
        return Mono.error(new UnauthorizedException("Sign in to manage your keys"));
    }

    private Map<String, Object> describe(ApiKeyStore.KeySummary key) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("keyId", key.keyId());
        entry.put("name", key.name());
        entry.put("prefix", ApiKeyFormat.displayHint(key.environment(), key.keyId()));
        // What the key can do today, not what it was issued with: ceilings may
        // have narrowed since.
        entry.put("scopes", ApiKeyScopes.effective(key.role(), key.scopes()));
        entry.put("createdAt", withOffset(key.createdAt()));
        entry.put("lastUsedAt", withOffset(key.lastUsedAt()));
        entry.put("expiresAt", withOffset(key.expiresAt()));
        entry.put("expired", key.expiresAt() != null && key.expiresAt().isBefore(LocalDateTime.now()));
        return entry;
    }

    /**
     * The columns hold server-local wall time with no zone. A browser reads an
     * ISO time without an offset as its own local time, so a key made in the
     * evening in the US showed tomorrow's date. Send the offset.
     */
    private static OffsetDateTime withOffset(LocalDateTime time) {
        return time == null ? null : time.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
