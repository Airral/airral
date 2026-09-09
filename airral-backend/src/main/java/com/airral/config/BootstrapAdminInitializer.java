package com.airral.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

/**
 * Promotes one named account to ADMIN at start-up.
 *
 * <p>Solves a real bootstrap problem. Nothing in the public API can create an
 * ADMIN, which is correct -- self-service platform administration would be a
 * hole -- but it means the first admin has to come from somewhere, and the only
 * alternative was hand-run SQL against production. That is worse than this: it
 * is unrepeatable, unreviewed, and leaves no trace of who granted what.
 *
 * <p>Not a privilege escalation path. Setting it requires the ability to change
 * this service's environment, which requires deploy access, which already
 * implies total control -- anyone who can set this variable could ship a build
 * that grants themselves anything. The trust boundary is unchanged; the
 * difference is that the grant is now declared in a workflow file rather than
 * typed into a database console.
 *
 * <p>Idempotent, and it only ever promotes an account that already exists: no
 * user is created here, so a typo grants nothing rather than creating a
 * mis-named administrator. Leaving the variable set means every deploy
 * re-asserts the grant, so remove it to make a demotion stick.
 */
@Component
public class BootstrapAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    private final DatabaseClient databaseClient;
    private final String bootstrapEmail;

    public BootstrapAdminInitializer(DatabaseClient databaseClient,
                                     @Value("${airral.auth.bootstrap-admin-email:}") String bootstrapEmail) {
        this.databaseClient = databaseClient;
        this.bootstrapEmail = bootstrapEmail == null ? "" : bootstrapEmail.trim();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void promote() {
        if (bootstrapEmail.isEmpty()) {
            return;
        }

        // email_verified is the whole security of this grant.
        //
        // Without it the match was lower(email) = lower(:email) and nothing else,
        // against a /register endpoint that is public and writes emailVerified
        // false for anyone who asks. Whoever registered this address first got
        // ADMIN and is_platform_admin on the next boot, and because the match is
        // case-insensitive with no LIMIT it did not even have to be the address
        // as written: registering Admin@Airral.com against a bootstrap of
        // admin@airral.com promoted that row too, and every other case variant
        // in the table alongside it.
        //
        // Nothing in this codebase can set email_verified except completing a
        // Google sign-in, whose address claim Google itself has verified -- there
        // is no verification mail, because SMTP does not work from Cloud Run
        // here. So the operational consequence is deliberate and worth stating:
        // the intended admin must sign in once with Google before this grant will
        // take. The branch below says so out loud rather than logging the same
        // "no such account" line whether the row is missing or merely unverified,
        // which is the difference between "I typed the wrong address" and "sign
        // in once and redeploy".
        databaseClient.sql("""
                        UPDATE users
                        SET role = 'ADMIN',
                            is_platform_admin = true
                        WHERE lower(email) = lower(:email)
                          AND email_verified IS TRUE
                          AND (role <> 'ADMIN' OR is_platform_admin IS NOT TRUE)
                        """)
                .bind("email", bootstrapEmail)
                .fetch()
                .rowsUpdated()
                .flatMap(updated -> updated != null && updated > 0
                        ? reactor.core.publisher.Mono.just(updated)
                        : databaseClient.sql("""
                                        SELECT COUNT(*) AS total
                                        FROM users
                                        WHERE lower(email) = lower(:email)
                                          AND email_verified IS NOT TRUE
                                        """)
                                .bind("email", bootstrapEmail)
                                .map((row, metadata) -> row.get("total", Long.class))
                                .one()
                                .doOnNext(unverified -> {
                                    if (unverified != null && unverified > 0) {
                                        log.warn("Bootstrap admin {} not granted: {} matching account(s) exist "
                                                        + "but none has a verified address. Sign in once with "
                                                        + "Google using that address, then redeploy.",
                                                bootstrapEmail, unverified);
                                    }
                                })
                                .thenReturn(0L))
                .subscribe(
                        updated -> {
                            if (updated != null && updated > 0) {
                                // Worth a log line at INFO: a privilege grant
                                // should be visible in the record of a boot,
                                // not something you have to query for.
                                log.warn("Bootstrap admin granted to {}", bootstrapEmail);
                            } else {
                                log.info("Bootstrap admin {} already an admin, or no such verified account",
                                        bootstrapEmail);
                            }
                        },
                        // Never fatal. A service that refuses to start because a
                        // convenience grant failed is worse than one that starts
                        // without it, and the grant can be retried by redeploying.
                        error -> log.error("Bootstrap admin promotion failed for {}: {}",
                                bootstrapEmail, error.getMessage()));
    }
}
