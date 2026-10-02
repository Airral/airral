package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.airral.security.ApiKeyStore;
import com.airral.security.LoginThrottle;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.IntConsumer;

/**
 * One-shot sync entry point for the {@code sync} profile.
 *
 * <p>Runs the external job sync to completion and exits, so the sync can be driven by an
 * external scheduler (GitHub Actions) instead of an in-process timer. This keeps the
 * Cloud Run service free to scale to zero: it serves reads only and never needs
 * CPU-always-allocated to finish background work after a response is sent.
 *
 * <p>Runs the same connectors as the in-process scheduler, so there is one implementation
 * of the ATS integrations rather than a second copy in the workflow.
 *
 * <p>Usage: {@code java -jar app.jar --spring.profiles.active=sync}
 */
@Component
@Profile("sync")
public class ExternalJobSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobSyncRunner.class);

    /**
     * How long a rate-limit window is kept. Windows are only consulted while
     * they are open, so a day is generous and leaves enough to look at if a key
     * is suspected of hammering the API.
     */
    private static final int USAGE_RETENTION_DAYS = 1;

    private final ExternalJobSyncService externalJobSyncService;
    private final ApiKeyStore apiKeyStore;
    private final LoginThrottle loginThrottle;
    private final Duration timeout;
    private final ApplicationContext context;
    private final IntConsumer exit;

    @Autowired
    public ExternalJobSyncRunner(
            ExternalJobSyncService externalJobSyncService,
            ApiKeyStore apiKeyStore,
            LoginThrottle loginThrottle,
            @Value("${airral.jobs.sync.cli-timeout-minutes:90}") int timeoutMinutes,
            ApplicationContext context) {
        this(externalJobSyncService, apiKeyStore, loginThrottle, timeoutMinutes, context, System::exit);
    }

    ExternalJobSyncRunner(
            ExternalJobSyncService externalJobSyncService,
            ApiKeyStore apiKeyStore,
            LoginThrottle loginThrottle,
            int timeoutMinutes,
            ApplicationContext context,
            IntConsumer exit) {
        this.externalJobSyncService = externalJobSyncService;
        this.apiKeyStore = apiKeyStore;
        this.loginThrottle = loginThrottle;
        this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
        this.context = context;
        this.exit = exit;
    }

    /**
     * Runs the sync, then ends the process with its result: 0 for a run that
     * finished or lost the lease to another one, 1 for one that did not.
     *
     * <p>Returning from here does not end the process. Any {@code @Scheduled}
     * bean the sync profile does not switch off starts Spring's task scheduler,
     * and its worker thread is not a daemon. The company sign-up clean-up
     * ({@code CompanyVerificationService.closeUnverifiedSignups}), added on
     * 2026-09-30, was the first such bean, and from then on every scheduled run
     * logged "One-shot sync finished", then sat idle until the workflow's
     * 120-minute limit cancelled it. A thread dump of an idle run showed
     * {@code main} gone and {@code scheduling-1} as the only non-daemon thread
     * left. So the context is closed and the process exits here, with the code
     * the workflow reads, however many such threads exist.
     */
    @Override
    public void run(ApplicationArguments args) {
        int exitCode = syncOnce();
        exit.accept(SpringApplication.exit(context, () -> exitCode));
    }

    private int syncOnce() {
        log.info("Starting one-shot external job sync (timeout {})", timeout);

        ExternalJobSyncResult result;
        try {
            result = externalJobSyncService.syncActiveSources().block(timeout);
        } catch (RuntimeException e) {
            log.error("External job sync failed", e);
            return 1;
        }

        if (result == null) {
            log.error("External job sync failed: the sync returned no result");
            return 1;
        }

        log.info(
                "One-shot sync finished: status={}, sources={}, seen={}, upserted={}, retired={}, expired={}, purged={}",
                result.status(),
                result.sourcesCount(),
                result.jobsSeen(),
                result.jobsUpserted(),
                result.jobsRetired(),
                result.jobsExpired(),
                result.jobsPurged());

        purgeApiKeyUsage();
        purgeLoginAttempts();

        // A lost lease race is a normal no-op, not a workflow failure. DEGRADED is:
        // a board we still believe in has not been read for a whole day, so its
        // postings are going stale. A board that only missed this run, and its
        // retry, is PARTIAL_SUCCESS: green, with a warning on the run page.
        if ("FAILED".equals(result.status()) || "DEGRADED".equals(result.status())) {
            log.error("External job sync failed: {}", result.status());
            return 1;
        }
        return 0;
    }

    /**
     * Drop spent rate-limit windows.
     *
     * <p>Rides along with the sync rather than running on an in-process timer,
     * for the same reason the sync itself does: the service scales to zero, so
     * a {@code @Scheduled} task fires only when an instance happens to be alive
     * and would silently never run during a quiet night. This job already runs
     * every four hours on a scheduler that exists.
     *
     * <p>Failure here is logged and swallowed. An uncollected window is
     * housekeeping; failing the sync over it would throw away a completed job
     * refresh for no benefit.
     */
    /** Same reasoning as the key usage purge: an external scheduler, not a timer. */
    private void purgeLoginAttempts() {
        try {
            Long removed = loginThrottle
                    .purgeBefore(LocalDateTime.now().minusDays(USAGE_RETENTION_DAYS))
                    .block(Duration.ofMinutes(1));
            log.info("Login attempt windows purged: {}", removed == null ? 0 : removed);
        } catch (RuntimeException e) {
            log.warn("Could not purge login attempt windows: {}", e.getMessage());
        }
    }

    private void purgeApiKeyUsage() {
        try {
            Long removed = apiKeyStore
                    .purgeUsageBefore(LocalDateTime.now().minusDays(USAGE_RETENTION_DAYS))
                    .block(Duration.ofMinutes(1));
            log.info("API key usage windows purged: {}", removed == null ? 0 : removed);
        } catch (RuntimeException e) {
            log.warn("Could not purge API key usage windows: {}", e.getMessage());
        }
    }
}
