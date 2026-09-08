package com.airral.security;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import com.airral.exception.TooManyLoginAttemptsException;

import reactor.core.publisher.Mono;

/**
 * Limits how fast passwords can be guessed.
 *
 * <p>Counts failures only. A correct password must not consume anyone's budget,
 * or a busy legitimate user locks themselves out and an attacker learns nothing
 * either way.
 *
 * <p>Two buckets per attempt, because they catch different attacks. The email
 * bucket stops one account being hammered from a botnet; the address bucket
 * stops one host spraying a leaked credential list across many accounts.
 * Neither alone is sufficient.
 *
 * <p>The address limit is deliberately much looser than the email one. An
 * office, a school or a mobile network puts hundreds of legitimate users behind
 * one address, and a tight limit there locks out people who did nothing wrong
 * while barely inconveniencing an attacker who can rotate hosts.
 */
@Service
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final DatabaseClient databaseClient;
    private final int maxPerEmail;
    private final int maxPerAddress;
    private final boolean enabled;

    public LoginThrottle(
            DatabaseClient databaseClient,
            @Value("${airral.auth.throttle.enabled:true}") boolean enabled,
            @Value("${airral.auth.throttle.max-per-email:5}") int maxPerEmail,
            @Value("${airral.auth.throttle.max-per-address:30}") int maxPerAddress) {
        this.databaseClient = databaseClient;
        this.enabled = enabled;
        this.maxPerEmail = maxPerEmail;
        this.maxPerAddress = maxPerAddress;
    }

    /**
     * Refuse the attempt if either bucket is already spent.
     *
     * <p>Checked before the password is verified, so a locked-out attacker
     * cannot keep testing candidates and cannot measure the difference between
     * a right and a wrong one by timing.
     */
    public Mono<Void> check(String email, String address) {
        if (!enabled) {
            return Mono.empty();
        }

        return attempts(emailKey(email))
                .flatMap(used -> used >= maxPerEmail
                        ? Mono.error(new TooManyLoginAttemptsException(WINDOW.toMinutes()))
                        : attempts(addressKey(address)))
                .flatMap(used -> used >= maxPerAddress
                        ? Mono.error(new TooManyLoginAttemptsException(WINDOW.toMinutes()))
                        : Mono.empty());
    }

    /**
     * Refuse the attempt on the address bucket alone.
     *
     * <p>For account creation, where there is no established account yet. The
     * two-bucket check is deliberately not used here: an attempt keyed on the
     * submitted email would let anyone burn a real user's sign-in budget by
     * repeatedly "registering" their address, locking them out of their own
     * account. Only the address is counted, which is the dimension that actually
     * limits bulk account creation.
     */
    public Mono<Void> checkAddress(String address) {
        if (!enabled) {
            return Mono.empty();
        }

        return attempts(addressKey(address))
                .flatMap(used -> used >= maxPerAddress
                        ? Mono.error(new TooManyLoginAttemptsException(WINDOW.toMinutes()))
                        : Mono.empty());
    }

    /** Count one account-creation attempt against the address bucket. */
    public Mono<Void> recordAddressAttempt(String address) {
        if (!enabled) {
            return Mono.empty();
        }

        return increment(addressKey(address))
                .onErrorResume(error -> {
                    log.error("Could not record account-creation attempt: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /** Record a failure against both buckets. */
    public Mono<Void> recordFailure(String email, String address) {
        if (!enabled) {
            return Mono.empty();
        }

        return increment(emailKey(email))
                .then(increment(addressKey(address)))
                .doOnSuccess(ignored -> log.warn("Failed sign-in for {} from {}",
                        maskEmail(email), address))
                // Never let bookkeeping turn a failed login into a 500. The
                // caller already got their password wrong; the useful signal is
                // the 401, not an error about counting it.
                .onErrorResume(error -> {
                    log.error("Could not record failed sign-in: {}", error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Clear the email bucket after a correct password.
     *
     * <p>Otherwise a user who mistypes four times and then succeeds stays one
     * slip away from a lockout for the rest of the window, which reads as the
     * system being broken.
     */
    public Mono<Void> recordSuccess(String email) {
        if (!enabled) {
            return Mono.empty();
        }

        return databaseClient.sql("DELETE FROM auth_attempt_windows WHERE bucket_key = :key")
                .bind("key", emailKey(email))
                .fetch()
                .rowsUpdated()
                .onErrorResume(error -> Mono.just(0L))
                .then();
    }

    /** Housekeeping: a window is only interesting while it is open. */
    public Mono<Long> purgeBefore(LocalDateTime cutoff) {
        return databaseClient.sql("DELETE FROM auth_attempt_windows WHERE window_start < :cutoff")
                .bind("cutoff", cutoff)
                .fetch()
                .rowsUpdated();
    }

    private Mono<Integer> attempts(String key) {
        return databaseClient.sql("""
                        SELECT COALESCE(SUM(attempts), 0) AS used
                        FROM auth_attempt_windows
                        WHERE bucket_key = :key
                          AND window_start > :since
                        """)
                .bind("key", key)
                .bind("since", LocalDateTime.now().minus(WINDOW))
                .map((row, meta) -> {
                    Number used = row.get("used", Number.class);
                    return used == null ? 0 : used.intValue();
                })
                .one()
                .defaultIfEmpty(0)
                // A counter that cannot be read must not lock everybody out.
                // Failing open here is the right trade: the alternative is a
                // database hiccup becoming a total sign-in outage.
                .onErrorResume(error -> {
                    log.error("Could not read login attempts, allowing: {}", error.getMessage());
                    return Mono.just(0);
                });
    }

    private Mono<Void> increment(String key) {
        return databaseClient.sql("""
                        INSERT INTO auth_attempt_windows (bucket_key, window_start, attempts)
                        VALUES (:key, date_trunc('minute', CURRENT_TIMESTAMP), 1)
                        ON CONFLICT (bucket_key, window_start)
                        DO UPDATE SET attempts = auth_attempt_windows.attempts + 1
                        """)
                .bind("key", key)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private static String emailKey(String email) {
        return "email:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "ip:" + (address == null || address.isBlank() ? "unknown" : address);
    }

    /** Enough to correlate log lines without writing an address into them. */
    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "unknown";
        }
        String local = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@'));
        return (local.isEmpty() ? "?" : local.charAt(0) + "***") + domain;
    }
}
