package com.airral.controller;

import com.airral.config.ClientIpConfig;
import com.airral.dto.request.LoginRequest;
import com.airral.dto.request.GoogleAuthRequest;
import com.airral.dto.request.RegisterRequest;
import com.airral.dto.request.VerifyEmailRequest;
import com.airral.dto.request.ResetPasswordRequest;
import com.airral.service.AccountVerificationService;
import com.airral.repository.UserRepository;
import com.airral.repository.OrganizationRepository;
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

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginThrottle loginThrottle;
    private final TokenVersionCache tokenVersionCache;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccountVerificationService accountVerificationService;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;

    public AuthController(AuthService authService,
                          LoginThrottle loginThrottle,
                          TokenVersionCache tokenVersionCache,
                          JwtTokenProvider jwtTokenProvider,
                          AccountVerificationService accountVerificationService,
                          UserRepository userRepository,
                          OrganizationRepository organizationRepository) {
        this.authService = authService;
        this.loginThrottle = loginThrottle;
        this.tokenVersionCache = tokenVersionCache;
        this.jwtTokenProvider = jwtTokenProvider;
        this.accountVerificationService = accountVerificationService;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
    }

    /**
     * Mark the caller's address as verified.
     * POST /api/auth/verify-email
     *
     * <p>The body is the Firebase ID token the browser holds after following the
     * link Firebase emailed. No session is needed -- the link is often opened on
     * a different device from the one that signed up -- because the token is
     * itself the proof, and it names the address.
     */
    @PostMapping("/verify-email")
    public Mono<ResponseEntity<Map<String, Object>>> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request,
            ServerWebExchange exchange) {

        String address = clientAddress(exchange);

        return loginThrottle.checkAddress(address)
                .then(loginThrottle.recordAddressAttempt(address))
                .then(accountVerificationService.verifyEmail(request.getIdToken()))
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "verified", result.verified(),
                        "email", result.email(),
                        "message", result.message())));
    }

    /**
     * Who the caller is, read from the database rather than the session token.
     * GET /api/auth/me
     *
     * <p>The token is minted at sign-in and says nothing about what has happened
     * since. The portals call this to learn that an address was verified on
     * another device, or that a company has been approved, without making anyone
     * sign in again.
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

        Long userId = jwtTokenProvider.getUserIdFromToken(extractToken(authHeader));

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new com.airral.exception.UnauthorizedException("Account not found")))
                .flatMap(user -> {
                    Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("userId", user.getId());
                    body.put("email", user.getEmail());
                    body.put("role", user.getRole() == null ? null : user.getRole().name());
                    body.put("emailVerified", user.isEmailVerified());
                    body.put("organizationId", user.getOrganizationId());
                    if (user.getOrganizationId() == null) {
                        return Mono.just(ResponseEntity.ok(body));
                    }
                    return organizationRepository.findById(user.getOrganizationId())
                            .map(org -> {
                                body.put("organizationName", org.getName());
                                body.put("organizationVerificationStatus", org.getVerificationStatus());
                                return ResponseEntity.ok(body);
                            })
                            .defaultIfEmpty(ResponseEntity.ok(body));
                });
    }

    /**
     * Set a new password from the link in a reset email.
     * POST /api/auth/reset-password
     *
     * <p>The link is sent by Firebase, from the portal, to whatever address the
     * person typed -- AIRRAL takes no part in that step, so it has nothing to say
     * about whether the address has an account and cannot leak it. What arrives
     * here is the Firebase ID token proving the link was followed.
     *
     * <p>Signs the account out everywhere on success; the caller signs in again
     * with the new password.
     */
    @PostMapping("/reset-password")
    public Mono<ResponseEntity<Map<String, Object>>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request,
            ServerWebExchange exchange) {

        String address = clientAddress(exchange);

        return loginThrottle.checkAddress(address)
                .then(loginThrottle.recordAddressAttempt(address))
                .then(accountVerificationService.resetPassword(request.getIdToken(), request.getPassword()))
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of(
                        "reset", true,
                        "message", "Your password has been changed. Sign in with the new one.")));
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
     * Create an account.
     * POST /api/auth/register
     *
     * <p>The service behind this has existed for a long time -- applicant,
     * new-organisation and invitation paths, all working -- but nothing was
     * mapped to it, so the endpoint the sign-up form posts to returned 404 in
     * production and nobody could create an account at all.
     *
     * <p>Which path runs is decided by the body, in AuthService.register: a
     * companyName creates an organisation, an invitationToken joins one, and
     * neither creates an applicant. The applicant path fixes the role and clears
     * the platform-admin flag itself rather than reading either from the
     * request, so this being public cannot be used to mint an administrator.
     *
     * <p>Throttled on the caller's address only. Rate limiting the submitted
     * email here would let anyone lock a real user out of signing in simply by
     * trying to register their address over and over.
     *
     * <p>A duplicate email answers 409 and says so, which does tell a caller
     * whether an address has an account. That is a deliberate trade: the
     * alternative is a vague failure that a genuine person cannot act on, and
     * there is no transactional email set up yet to resolve it out of band. The
     * address throttle is what keeps enumeration slow.
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            ServerWebExchange exchange) {

        String address = clientAddress(exchange);

        return loginThrottle.checkAddress(address)
                .then(loginThrottle.recordAddressAttempt(address))
                .then(authService.register(request))
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * Sign in with Google.
     * POST /api/auth/google
     *
     * <p>Left behind by the /register fix above: that mapping came back and
     * this one did not, so the same failure was still live. SecurityConfig has
     * allow-listed POST /api/auth/google all along and
     * AuthService.loginWithGoogle was complete, but with nothing mapped to the
     * path the "Continue with Google" button on both apply.airral.com and
     * app.airral.com posted to a route that answered 404 "No static resource
     * api/auth/google." -- while /api/auth/login answered 400 at the same
     * instant, which is what ruled out the app being down.
     *
     * <p>Throttled on the caller's address only, like /register and for the
     * same reason: a Google address that has no account yet gets one created
     * here, so this is an account-creation path. There is no email to key the
     * second bucket on in any case -- the request carries a signed credential
     * and nothing else, and the address inside it is not known until Google's
     * signature has been checked.
     *
     * <p>200 rather than /register's 201, because one route serves both an
     * existing account signing in and a new one being created and which of the
     * two happened is not decided until the credential is verified inside the
     * service. AuthResponse.message carries the distinction.
     */
    @PostMapping("/google")
    public Mono<ResponseEntity<AuthResponse>> loginWithGoogle(
            @Valid @RequestBody GoogleAuthRequest request,
            ServerWebExchange exchange) {

        String address = clientAddress(exchange);

        return loginThrottle.checkAddress(address)
                .then(loginThrottle.recordAddressAttempt(address))
                .then(authService.loginWithGoogle(request))
                .map(ResponseEntity::ok);
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
     * The caller's address as seen from outside, and the key every throttle
     * bucket on this controller is counted against.
     *
     * <p>This used to parse X-Forwarded-For here and take its left-most entry.
     * Cloud Run appends the real client address to a caller-supplied header
     * rather than replacing it, so the left-most entry was the caller's own
     * text and rotating it bought a fresh bucket per request -- which mattered
     * because /register and /google check the address bucket only, and it is
     * the only limit that spans accounts. Resolution now happens once, in
     * {@link ClientIpConfig}, in the forwarded-header transformer, which is the
     * only place that still sees the chain: it runs before the exchange exists
     * and strips the header afterwards.
     */
    private String clientAddress(ServerWebExchange exchange) {
        return ClientIpConfig.clientAddress(exchange);
    }

}
