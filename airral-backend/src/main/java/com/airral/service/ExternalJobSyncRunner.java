package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;

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

    private final ExternalJobSyncService externalJobSyncService;
    private final Duration timeout;

    public ExternalJobSyncRunner(
            ExternalJobSyncService externalJobSyncService,
            @Value("${airral.jobs.sync.cli-timeout-minutes:90}") int timeoutMinutes) {
        this.externalJobSyncService = externalJobSyncService;
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

        // A lost lease race is a normal no-op, not a workflow failure.
        if ("FAILED".equals(result.status())) {
            throw new IllegalStateException("External job sync failed: " + result.status());
        }
    }
}
