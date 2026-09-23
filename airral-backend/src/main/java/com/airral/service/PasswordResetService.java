package com.airral.service;

import com.airral.domain.User;
import com.airral.exception.BadRequestException;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Forgot-password and reset-password.
 *
 * <p>Three properties matter more than the happy path, and each is enforced here
 * rather than left to the caller:
 *
 * <ul>
 *   <li><b>No account enumeration.</b> {@link #requestReset} completes the same
 *       way whether or not the address has an account, and the email is sent off
 *       the request thread so the response time does not say either.</li>
 *   <li><b>The token is only ever in the email.</b> The table holds a SHA-256 of
 *       it, links are single-use, expire after {@link #TOKEN_LIFETIME}, and a
 *       successful reset voids every other outstanding link for the account.</li>
 *   <li><b>A reset ends every session.</b> Someone resetting a password is often
 *       doing it because they think someone else has it, so every token issued
 *       before the reset stops working.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    /**
     * Links one account may be sent per hour. Without it the endpoint is a way to
     * fill somebody's inbox from AIRRAL's sending domain, which costs them their
     * patience and costs AIRRAL its sender reputation.
     */
    static final int MAX_REQUESTS_PER_HOUR = 3;

    /** Long enough to cover an SMTP send, so both outcomes answer at the same time. */
    static final Duration MIN_RESPONSE_TIME = Duration.ofMillis(2500);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseClient databaseClient;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenVersionCache tokenVersionCache;
    private final LoginThrottle loginThrottle;
    private final CandidateEmailService emailService;
    private final String resetPageUrl;
    private final Duration minResponseTime;

    public PasswordResetService(
            DatabaseClient databaseClient,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenVersionCache tokenVersionCache,
            LoginThrottle loginThrottle,
            CandidateEmailService emailService,
            @Value("${airral.auth.password-reset.page-url:https://apply.airral.com/reset-password}") String resetPageUrl) {
        this.databaseClient = databaseClient;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenVersionCache = tokenVersionCache;
        this.loginThrottle = loginThrottle;
        this.emailService = emailService;
        this.resetPageUrl = resetPageUrl;
        this.minResponseTime = MIN_RESPONSE_TIME;
    }


    /**
     * Sends a reset link if the address belongs to an active account, and does
     * nothing observable otherwise.
     *
     * <p>The email is sent inside the request, not after it: Cloud Run throttles
     * CPU once a response has gone out, so work left running after the reply --
     * the reason the job sync lives in GitHub Actions -- can stall until the next
     * request happens to arrive. Sending in-request would make a real account
     * answer slower than a missing one, so every call is held to at least
     * {@link #MIN_RESPONSE_TIME}; the answer then arrives at the same moment
     * whether or not anything was sent.
     */
    public Mono<Void> requestReset(String email) {
        String address = email == null ? "" : email.trim();
        Mono<Void> work = userRepository.findByEmail(address)
                .filter(User::isActive)
                .flatMap(user -> recentRequestCount(user.getId())
                        .flatMap(recent -> {
                            if (recent >= MAX_REQUESTS_PER_HOUR) {
                                log.info("Password reset for user {} not sent: {} already in the last hour",
                                        user.getId(), recent);
                                return Mono.<Void>empty();
                            }
                            return issueAndSend(user);
                        }))
                // Nothing that goes wrong here may reach the caller: an error that
                // only happens for real accounts is an enumeration oracle.
                .onErrorResume(error -> {
                    log.warn("Password reset request failed: {}", error.toString());
                    return Mono.empty();
                })
                .then();
        return Mono.when(work, Mono.delay(minResponseTime));
    }

    /**
     * Sets a new password from a reset link. Fails with one message for every kind
     * of bad token -- unknown, expired, already used -- so the response does not
     * say which.
     */
    public Mono<Void> resetPassword(String token, String newPassword) {
        String hash = sha256(token == null ? "" : token.trim());
        return databaseClient.sql("""
                        UPDATE password_reset_tokens
                        SET used_at = CURRENT_TIMESTAMP
                        WHERE token_hash = :hash
                          AND used_at IS NULL
                          AND expires_at > CURRENT_TIMESTAMP
                        RETURNING user_id
                        """)
                .bind("hash", hash)
                .map((row, meta) -> row.get("user_id", Long.class))
                .one()
                // Consumed in the same statement that checks it, so two requests
                // racing on one link cannot both succeed.
                .switchIfEmpty(Mono.error(invalidLink()))
                .flatMap(userId -> userRepository.findById(userId)
                        .filter(User::isActive)
                        .switchIfEmpty(Mono.error(invalidLink())))
                .flatMap(user -> {
                    user.setPasswordHash(passwordEncoder.encode(newPassword));
                    return userRepository.save(user);
                })
                .flatMap(user -> databaseClient.sql("""
                                UPDATE password_reset_tokens
                                SET used_at = CURRENT_TIMESTAMP
                                WHERE user_id = :userId AND used_at IS NULL
                                """)
                        .bind("userId", user.getId())
                        .fetch()
                        .rowsUpdated()
                        .then(tokenVersionCache.revokeAll(user.getId()))
                        // Whoever just proved they own the inbox should not stay
                        // locked out by failures from before they did.
                        .then(loginThrottle.recordSuccess(user.getEmail()))
                        .doOnSuccess(ignored -> log.info("Password reset completed for user {}", user.getId())))
                .then();
    }

    private Mono<Long> recentRequestCount(Long userId) {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS n FROM password_reset_tokens
                        WHERE user_id = :userId AND created_at > :since
                        """)
                .bind("userId", userId)
                .bind("since", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
                .map((row, meta) -> row.get("n", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    private Mono<Void> issueAndSend(User user) {
        String token = newToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(TOKEN_LIFETIME);
        return databaseClient.sql("""
                        INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
                        VALUES (:userId, :hash, :expiresAt)
                        """)
                .bind("userId", user.getId())
                .bind("hash", sha256(token))
                .bind("expiresAt", expiresAt)
                .fetch()
                .rowsUpdated()
                .then(emailService.sendEmail(user.getEmail(), "Reset your AIRRAL password", resetEmail(token)))
                .doOnSuccess(ignored -> log.info("Password reset link issued for user {}", user.getId()));
    }

    String resetLink(String token) {
        // In the fragment, not the query string: a fragment is never sent to a
        // server, so the token stays out of access logs, proxies and Referer
        // headers. The page reads it and removes it from the address bar.
        return resetPageUrl + "#token=" + token;
    }

    private String resetEmail(String token) {
        String link = resetLink(token);
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>Reset your AIRRAL password</title></head>
                <body style="margin:0;padding:0;background:#f6f7f6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#111;">
                  <div style="max-width:560px;margin:0 auto;padding:32px 16px;">
                    <div style="font-size:20px;font-weight:700;color:#007C6D;margin-bottom:24px;">AIRRAL</div>
                    <div style="background:#fff;border:1px solid #e1e5e9;border-radius:8px;padding:32px;">
                      <p style="margin:0 0 16px;font-size:16px;">Someone asked to reset the password for this AIRRAL account.</p>
                      <p style="margin:0 0 24px;"><a href="%s" style="display:inline-block;background:#007C6D;color:#fff;text-decoration:none;padding:12px 20px;border-radius:6px;font-weight:600;">Choose a new password</a></p>
                      <p style="margin:0 0 8px;font-size:14px;color:#444;">This link works once and expires in %d minutes.</p>
                      <p style="margin:0;font-size:14px;color:#444;">If you didn't ask for this, you can ignore this email. Your password won't change.</p>
                    </div>
                  </div>
                </body></html>
                """.formatted(link, TOKEN_LIFETIME.toMinutes());
    }

    private static BadRequestException invalidLink() {
        return new BadRequestException("This reset link is invalid or has expired. Request a new one.");
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
