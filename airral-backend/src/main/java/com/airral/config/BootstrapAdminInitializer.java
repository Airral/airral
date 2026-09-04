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

        databaseClient.sql("""
                        UPDATE users
                        SET role = 'ADMIN',
                            is_platform_admin = true
                        WHERE lower(email) = lower(:email)
                          AND (role <> 'ADMIN' OR is_platform_admin IS NOT TRUE)
                        """)
                .bind("email", bootstrapEmail)
                .fetch()
                .rowsUpdated()
                .subscribe(
                        updated -> {
                            if (updated != null && updated > 0) {
                                // Worth a log line at INFO: a privilege grant
                                // should be visible in the record of a boot,
                                // not something you have to query for.
                                log.warn("Bootstrap admin granted to {}", bootstrapEmail);
                            } else {
                                log.info("Bootstrap admin {} already an admin, or no such account",
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
