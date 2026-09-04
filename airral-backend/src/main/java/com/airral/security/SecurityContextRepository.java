package com.airral.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class SecurityContextRepository implements ServerSecurityContextRepository {

    private final AuthenticationManager authenticationManager;
    private final ApiKeyAuthenticationManager apiKeyAuthenticationManager;

    public SecurityContextRepository(AuthenticationManager authenticationManager,
                                     ApiKeyAuthenticationManager apiKeyAuthenticationManager) {
        this.authenticationManager = authenticationManager;
        this.apiKeyAuthenticationManager = apiKeyAuthenticationManager;
    }

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        throw new UnsupportedOperationException("Not supported - stateless JWT authentication");
    }

    /**
     * Both credential types arrive in the same header, so this decides which one
     * is present and hands it to the manager that understands it.
     *
     * <p>The two formats cannot be confused: a session token is a JWE, which is
     * exactly five dot-separated segments, and an API key begins
     * {@code airral_ak_} and contains no dots. Anything matching neither is
     * ignored, which Spring turns into a 401 -- the same treatment an
     * unrecognised token got before keys existed.
     *
     * <p>Both managers produce the same principal shape, so nothing downstream
     * of here knows or cares which branch ran.
     */
    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.empty();
        }

        String presented = authHeader.substring(7);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(presented, presented);

        if (ApiKeyFormat.looksLikeApiKey(presented)) {
            return this.apiKeyAuthenticationManager.authenticate(auth)
                    .map(SecurityContextImpl::new);
        }

        if (!looksLikeEncryptedJwe(presented)) {
            return Mono.empty();
        }

        return this.authenticationManager.authenticate(auth)
                .map(SecurityContextImpl::new);
    }

    private boolean looksLikeEncryptedJwe(String token) {
        return token != null && token.split("\\.", -1).length == 5;
    }
}
