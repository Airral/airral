package com.airral.controller;

import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.exception.UnauthorizedException;
import com.airral.security.LoginThrottle;
import com.airral.service.AuthService;
import jakarta.validation.Valid;
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

    public AuthController(AuthService authService, LoginThrottle loginThrottle) {
        this.authService = authService;
        this.loginThrottle = loginThrottle;
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
     * The caller's address as seen from outside.
     *
     * <p>Cloud Run terminates TLS and forwards the original client, so the
     * socket address is a Google front end and the same for everybody. Reading
     * it instead of X-Forwarded-For would put every user in one bucket and lock
     * out the world on the first attack.
     *
     * <p>Takes the first entry, which is the original client. Later entries are
     * proxies, and a client-supplied header could prepend anything -- but on
     * Cloud Run the platform rewrites this, so the first entry is trustworthy
     * here in a way it would not be behind an arbitrary proxy.
     */
    private String clientAddress(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    /**
     * Google Identity Services endpoint
     * POST /api/auth/google
     */
    @PostMapping("/google")
    public Mono<ResponseEntity<AuthResponse>> googleLogin(@Valid @RequestBody GoogleAuthRequest request) {
        return authService.loginWithGoogle(request)
                .map(ResponseEntity::ok);
    }

    /**
     * Register endpoint
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * Test endpoint to verify JWT authentication
     * GET /api/auth/me
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<String>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Missing or invalid authorization header"));
        }
        return Mono.just(ResponseEntity.ok("Authenticated successfully"));
    }

    /**
     * Logout endpoint
     * DELETE /api/auth/logout
     */
    @DeleteMapping("/logout")
    public Mono<ResponseEntity<Map<String, String>>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // For JWT-based stateless auth, logout is primarily client-side (token deletion)
        // Server can optionally track token blacklist if needed
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .<Map<String, String>>body(Map.of("message", "Invalid or missing token")));
        }
        
        // Token invalidation would go here (optional blacklist check)
        // For now, we just confirm logout on client side
        return Mono.just(ResponseEntity.ok(Map.of("message", "Logged out successfully")));
    }
}
