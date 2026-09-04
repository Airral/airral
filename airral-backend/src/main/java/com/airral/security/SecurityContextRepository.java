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

    /**
     * Where the reason an API key was refused is left for the authentication
     * entry point to render.
     *
     * <p>It has to travel by exchange attribute rather than by exception: the
     * reason is only known here, while the 401 body is only written there, and
     * a thrown ResponseStatusException does not carry its reason into the
     * response body unless message inclusion is switched on globally -- which
     * would start surfacing framework internals on every other error too.
     */
    public static final String KEY_REJECTION_ATTRIBUTE = "airral.apiKeyRejection";

    private final AuthenticationManager authenticationManager;
    private final ApiKeyAuthenticationManager apiKeyAuthenticationManager;
    private final ApiKeyStore apiKeyStore;

    public SecurityContextRepository(AuthenticationManager authenticationManager,
                                     ApiKeyAuthenticationManager apiKeyAuthenticationManager,
                                     ApiKeyStore apiKeyStore) {
        this.authenticationManager = authenticationManager;
        this.apiKeyAuthenticationManager = apiKeyAuthenticationManager;
        this.apiKeyStore = apiKeyStore;
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
                    // Explicit witness: without it the chain infers
                    // Mono<SecurityContextImpl> and switchIfEmpty below will
                    // not accept an empty Mono<SecurityContext>.
                    .<SecurityContext>map(SecurityContextImpl::new)
                    // On a miss, spend one more query working out why, so the
                    // holder is told whether the key expired, was revoked or was
                    // mistyped instead of getting a bare 401. Only on failure,
                    // so the indexed lookup every successful request makes is
                    // untouched.
                    .switchIfEmpty(Mono.defer(() -> apiKeyStore
                            .explainMiss(ApiKeyFormat.sha256(presented))
                            .doOnNext(reason -> exchange.getAttributes()
                                    .put(KEY_REJECTION_ATTRIBUTE, reason))
                            .then(Mono.empty())));
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
