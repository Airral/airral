package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnProperty(prefix = "airral.jobs.sync", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExternalJobSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobSyncScheduler.class);

    private final ExternalJobSyncService externalJobSyncService;
    private final boolean runOnStartup;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ExternalJobSyncScheduler(
            ExternalJobSyncService externalJobSyncService,
            @Value("${airral.jobs.sync.run-on-startup:false}") boolean runOnStartup) {
        this.externalJobSyncService = externalJobSyncService;
        this.runOnStartup = runOnStartup;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (runOnStartup) {
            triggerSync("startup");
        }
    }

    @Scheduled(
            fixedDelayString = "${airral.jobs.sync.fixed-delay-ms:14400000}",
            initialDelayString = "${airral.jobs.sync.initial-delay-ms:30000}"
    )
    public void syncOnSchedule() {
        triggerSync("scheduled");
    }

    private void triggerSync(String reason) {
        if (!running.compareAndSet(false, true)) {
            log.info("Skipping {} external job sync because another sync is running", reason);
            return;
        }

        externalJobSyncService.syncActiveSources()
                .doOnSubscribe(subscription -> log.info("Starting {} external job sync", reason))
                .doOnSuccess(result -> log.info(
                        "Finished {} external job sync: status={}, sources={}, seen={}, upserted={}, expired={}",
                        reason,
                        result.status(),
                        result.sourcesCount(),
                        result.jobsSeen(),
                        result.jobsUpserted(),
                        result.jobsExpired()))
                .doOnError(error -> log.warn("External job sync failed during {} run: {}", reason, error.getMessage()))
                .doFinally(signal -> running.set(false))
                .subscribe();
    }
}
