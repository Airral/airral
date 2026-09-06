package com.airral.controller;

import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.UnauthorizedException;
import com.airral.security.JwtTokenProvider;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import com.airral.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle loginThrottle;
    private final TokenVersionCache tokenVersionCache;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthService authService,
                          LoginThrottle loginThrottle,
                          TokenVersionCache tokenVersionCache,
                          JwtTokenProvider jwtTokenProvider) {
        this.authService = authService;
        this.loginThrottle = loginThrottle;
        this.tokenVersionCache = tokenVersionCache;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Login endpoint
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {

        String address = clientAddress(exchange);

        // Checked before the password is verified, so a locked-out attacker
        // cannot keep testing candidates -- and cannot time the difference
        // between a right and a wrong one.
        return loginThrottle.check(request.getEmail(), address)
                .then(authService.login(request))
                .flatMap(response -> loginThrottle.recordSuccess(request.getEmail())
                        .thenReturn(ResponseEntity.ok(response)))
                .onErrorResume(error -> {
                    // Only a rejected credential counts. A throttle response,
                    // or a database failure, must not spend the user's budget
                    // for them.
                    if (error instanceof UnauthorizedException) {
                        return loginThrottle.recordFailure(request.getEmail(), address)
                                .then(Mono.error(error));
                    }
                    return Mono.error(error);
                });
    }

    /**
     * Sign out everywhere.
     * POST /api/auth/revoke-sessions
     *
     * <p>Invalidates every outstanding token for the caller, including the one
     * making this request. The ordinary logout is client-side only -- it drops
     * the token from storage, which does nothing about a copy someone else
     * already has. This is the one that helps after a laptop goes missing.
     */
    @PostMapping("/revoke-sessions")
    public Mono<ResponseEntity<Map<String, Object>>> revokeSessions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

        Long userId = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));

        return tokenVersionCache.revokeAll(userId)
                .map(version -> ResponseEntity.ok(Map.<String, Object>of(
                        "revoked", true,
                        "message", "Every session for this account has been signed out. "
                                + "You will need to sign in again.")));
    }

    private String extractToken(String authHeader) {
        return authHeader != null && authHeader.startsWith("Bearer ")
                ? authHeader.substring(7)
                : authHeader;
    }

    /**
     * The caller's address as seen from outside.
     *
     * <p>Deliberately defensive at every step. Cloud Run terminates TLS, so the
     * socket peer is a Google front end; forward-headers-strategy makes Spring
     * apply X-Forwarded-For to the request and then <em>remove</em> the header,
     * so code that reads it directly finds nothing. Worse, the resulting
     * InetSocketAddress can be unresolved -- getAddress() returns null -- and
     * dereferencing it threw an NPE that turned every sign-in into a 500.
     *
     * <p>An address is only used to bucket rate limiting. Failing to determine
     * one must never fail the request: "unknown" simply shares a bucket.
     */
    private String clientAddress(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }

        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null) {
            if (remote.getAddress() != null) {
                return remote.getAddress().getHostAddress();
            }
            // Unresolved, which is normal once the forwarded header has been
            // applied and stripped. The host string still names the client.
            if (remote.getHostString() != null && !remote.getHostString().isBlank()) {
                return remote.getHostString();
            }
        }
        return "unknown";
    }

}
