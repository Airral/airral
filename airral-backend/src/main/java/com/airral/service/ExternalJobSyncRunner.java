package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.airral.security.ApiKeyStore;

import java.time.Duration;
import java.time.LocalDateTime;

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
    private final Duration timeout;

    public ExternalJobSyncRunner(
            ExternalJobSyncService externalJobSyncService,
            ApiKeyStore apiKeyStore,
            @Value("${airral.jobs.sync.cli-timeout-minutes:90}") int timeoutMinutes) {
        this.externalJobSyncService = externalJobSyncService;
        this.apiKeyStore = apiKeyStore;
        this.timeout = Duration.ofMinutes(Math.max(1, timeoutMinutes));
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting one-shot external job sync (timeout {})", timeout);

        ExternalJobSyncResult result = externalJobSyncService.syncActiveSources().block(timeout);

        if (result == null) {
            throw new IllegalStateException("External job sync returned no result");
        }

        log.info(
                "One-shot sync finished: status={}, sources={}, seen={}, upserted={}, expired={}, purged={}",
                result.status(),
                result.sourcesCount(),
                result.jobsSeen(),
                result.jobsUpserted(),
                result.jobsExpired(),
                result.jobsPurged());

        purgeApiKeyUsage();

        // A lost lease race is a normal no-op, not a workflow failure.
        if ("FAILED".equals(result.status())) {
            throw new IllegalStateException("External job sync failed: " + result.status());
        }
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
