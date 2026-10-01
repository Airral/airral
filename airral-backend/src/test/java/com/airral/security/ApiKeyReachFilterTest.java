package com.airral.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;

import reactor.core.publisher.Mono;

class ApiKeyReachFilterTest {

    private final ApiKeyReachFilter filter = new ApiKeyReachFilter();

    private static Authentication apiKey(String role) {
        return new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of(
                new SimpleGrantedAuthority(role),
                new SimpleGrantedAuthority(ApiKeyScopes.CREDENTIAL_AUTHORITY),
                new SimpleGrantedAuthority(ApiKeyScopes.authority(ApiKeyScopes.JOBS_READ))));
    }

    private static Authentication session(String role) {
        return new UsernamePasswordAuthenticationToken("owner@example.com", null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    /** Runs the filter and reports whether the request went on down the chain. */
    private static boolean passes(ApiKeyReachFilter filter, MockServerWebExchange exchange, Authentication auth) {
        AtomicBoolean reached = new AtomicBoolean(false);
        Mono<Void> run = filter.filter(exchange, ex -> {
            reached.set(true);
            return Mono.empty();
        });
        if (auth != null) {
            run = run.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        }
        run.block();
        return reached.get();
    }

    private static MockServerWebExchange get(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path));
    }

    @Test
    @DisplayName("an ADMIN key cannot reach the admin API")
    void adminKeyIsRefusedOnAdminApi() {
        MockServerWebExchange exchange = get("/api/admin/companies");
        assertFalse(passes(filter, exchange, apiKey("ADMIN")));
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("any key is refused anywhere on /api, including the key endpoints themselves")
    void keysAreRefusedOnTheRestApi() {
        for (String path : List.of("/api/applications", "/api/account/api-keys", "/api/candidate/jobs")) {
            MockServerWebExchange exchange = get(path);
            assertFalse(passes(filter, exchange, apiKey("APPLICANT")), path);
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    @DisplayName("a key reaches /mcp and the health check")
    void keysReachMcp() {
        assertTrue(passes(filter, MockServerWebExchange.from(MockServerHttpRequest.post("/mcp")), apiKey("APPLICANT")));
        assertTrue(passes(filter, get("/actuator/health"), apiKey("APPLICANT")));
    }

    @Test
    @DisplayName("a lookalike path is not /mcp")
    void lookalikeIsNotMcp() {
        assertFalse(ApiKeyReachFilter.keysMayReach("/mcpx"));
        assertFalse(ApiKeyReachFilter.keysMayReach("/api/mcp"));
        assertTrue(ApiKeyReachFilter.keysMayReach("/mcp/"));
    }

    @Test
    @DisplayName("sessions and anonymous requests are left to the normal rules")
    void sessionsPass() {
        assertTrue(passes(filter, get("/api/admin/companies"), session("ADMIN")));
        assertTrue(passes(filter, get("/api/applications"), session("HR_MANAGER")));
        assertTrue(passes(filter, get("/api/candidate/jobs"), null));
    }
}
