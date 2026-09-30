package com.airral.security;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Keeps API keys on /mcp.
 *
 * <p>A key's principal carries its owner's role, so without this a key passed
 * every role rule in SecurityConfig: an ADMIN key could approve companies on
 * /api/admin/**, and the other roles' endpoints were held back only because
 * their controllers re-parse the header as a JWT and fail. Scopes are checked
 * inside /mcp and nowhere else, so /mcp is the only place a key may reach.
 *
 * <p>Refused with a 403 that says so, not a 500: people will try their key
 * against the REST API, and the answer should tell them where it works.
 *
 * <p>Runs inside the security chain, before authorisation, so the principal is
 * already known. Not a {@code @Component}: registered as a bean, Spring would
 * also run it outside the security chain, where there is no principal to see.
 */
public class ApiKeyReachFilter implements WebFilter {

    private static final byte[] REFUSAL = ("{\"status\":403,\"error\":\"API_KEY_NOT_ALLOWED\","
            + "\"message\":\"AIRRAL API keys only work with the MCP endpoint (/mcp). "
            + "Sign in to AIRRAL to use anything else.\"}").getBytes(StandardCharsets.UTF_8);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (keysMayReach(exchange.getRequest().getPath().pathWithinApplication().value())) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(ApiKeyReachFilter::isApiKey)
                .defaultIfEmpty(false)
                .flatMap(isKey -> isKey ? refuse(exchange) : chain.filter(exchange));
    }

    static boolean keysMayReach(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/") || path.equals("/actuator/health");
    }

    public static boolean isApiKey(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ApiKeyScopes.CREDENTIAL_AUTHORITY.equals(authority.getAuthority()));
    }

    private static Mono<Void> refuse(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(REFUSAL)));
    }
}
