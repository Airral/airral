package com.airral.controller;

import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.response.AuthResponse;
import com.airral.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = {"http://localhost:4200", "http://localhost:4201", "http://localhost:4202", "http://localhost:4203"})
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Login endpoint
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request)
                .map(ResponseEntity::ok);
    }

    /**
     * Register endpoint
     * POST /api/auth/register
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<Map<String, String>>> register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request)
                .map(message -> ResponseEntity.status(HttpStatus.CREATED)
                        .<Map<String, String>>body(Map.of("message", message)));
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
