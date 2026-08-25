package com.airral.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class InternalJobCatalogReconciler {

    private static final Logger log = LoggerFactory.getLogger(InternalJobCatalogReconciler.class);

    private final InternalJobCatalogProjectionService projectionService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public InternalJobCatalogReconciler(InternalJobCatalogProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        trigger("startup");
    }

    @Scheduled(
            fixedDelayString = "${airral.jobs.internal-reconcile.fixed-delay-ms:300000}",
            initialDelayString = "${airral.jobs.internal-reconcile.initial-delay-ms:60000}"
    )
    public void reconcileOnSchedule() {
        trigger("scheduled");
    }

    private void trigger(String reason) {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        projectionService.reconcileOpenJobs()
                .doOnSuccess(count -> log.info("Reconciled {} AIRRAL employer jobs during {} run", count, reason))
                .doOnError(error -> log.warn("AIRRAL employer job reconciliation failed during {} run: {}", reason, error.getMessage()))
                .doFinally(signal -> running.set(false))
                .subscribe();
    }
}
