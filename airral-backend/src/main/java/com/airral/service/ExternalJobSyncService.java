package com.airral.service;

import com.airral.dto.response.CandidateJobSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ExternalJobSyncService {

    private static final Logger log = LoggerFactory.getLogger(ExternalJobSyncService.class);
    private static final String SYNC_LOCK_NAME = "external-job-sync";

    private final ExternalJobPostingStore externalJobPostingStore;
    private final CandidateJobSearchService candidateJobSearchService;
    private final int retentionDays;
    private final int limitPerSource;
    private final int leaseMinutes;
    private final int sourceConcurrency;
    private final int maxSourcesPerRun;
    private final String syncOwnerId;

    public ExternalJobSyncService(
            ExternalJobPostingStore externalJobPostingStore,
            CandidateJobSearchService candidateJobSearchService,
            @Value("${airral.jobs.retention-days:60}") int retentionDays,
            @Value("${airral.jobs.sync.limit-per-source:500}") int limitPerSource,
            @Value("${airral.jobs.sync.lease-minutes:50}") int leaseMinutes,
            @Value("${airral.jobs.sync.source-concurrency:6}") int sourceConcurrency,
            @Value("${airral.jobs.sync.max-sources-per-run:500}") int maxSourcesPerRun,
            @Value("${spring.application.name:airral-backend}") String applicationName) {
        this.externalJobPostingStore = externalJobPostingStore;
        this.candidateJobSearchService = candidateJobSearchService;
        this.retentionDays = Math.max(1, retentionDays);
        this.limitPerSource = Math.max(1, Math.min(limitPerSource, 500));
        this.leaseMinutes = Math.max(5, leaseMinutes);
        this.sourceConcurrency = Math.max(1, Math.min(sourceConcurrency, 20));
        this.maxSourcesPerRun = Math.max(1, maxSourcesPerRun);
        this.syncOwnerId = applicationName + "-" + UUID.randomUUID();
    }

    public Mono<ExternalJobSyncResult> syncActiveSources() {
        return externalJobPostingStore.acquireSyncLease(
                        SYNC_LOCK_NAME,
                        syncOwnerId,
                        Duration.ofMinutes(leaseMinutes))
                .flatMap(leaseAcquired -> {
                    if (!leaseAcquired) {
                        log.info("Skipping external job sync because another instance owns the sync lease");
                        return Mono.just(new ExternalJobSyncResult("SKIPPED_LOCKED", 0, 0, 0, 0));
                    }

                    return runWithLease()
                            .flatMap(result -> releaseLease().thenReturn(result))
                            .onErrorResume(error -> releaseLease().then(Mono.error(error)));
                });
    }

    private Mono<ExternalJobSyncResult> runWithLease() {
        return externalJobPostingStore.createSyncRun()
                .flatMap(this::syncActiveSourcesForRun);
    }

    private Mono<ExternalJobSyncResult> syncActiveSourcesForRun(Long runId) {
        return externalJobPostingStore.findActiveSources()
                .take(maxSourcesPerRun)
                .collectList()
                .flatMap(sources -> Flux.fromIterable(sources)
                        .flatMap(this::syncSource, sourceConcurrency)
                        .collectList()
                        .flatMap(sourceResults -> finishRun(runId, sources, sourceResults)))
                .onErrorResume(error -> externalJobPostingStore.completeSyncRun(
                                runId,
                                "FAILED",
                                0,
                                0,
                                0,
                                0,
                                error.getMessage())
                        .then(Mono.error(error)));
    }

    private Mono<SourceSyncResult> syncSource(ExternalJobSourceRecord source) {
        return candidateJobSearchService.getLiveRecommendedJobs(
                        source.sourceType(),
                        source.boardToken(),
                        limitPerSource,
                        retentionDays,
                        null,
                        null)
                .collectList()
                .flatMap(jobs -> upsertJobs(source, jobs)
                        .flatMap(jobsUpserted -> externalJobPostingStore.markSourceSuccess(source.id())
                                .thenReturn(new SourceSyncResult(source, jobs.size(), jobsUpserted, null))))
                .onErrorResume(error -> {
                    log.warn("External job sync failed for {} {}: {}", source.sourceType(), source.boardToken(), error.getMessage());
                    return externalJobPostingStore.markSourceError(source.id(), error.getMessage())
                            .thenReturn(new SourceSyncResult(source, 0, 0, error.getMessage()));
                });
    }

    private Mono<Integer> upsertJobs(ExternalJobSourceRecord source, List<CandidateJobSummaryResponse> jobs) {
        return Flux.fromIterable(jobs)
                .flatMap(job -> externalJobPostingStore.upsertJob(source, job, retentionDays), 8)
                .reduce(0, (count, rowsUpdated) -> count + (rowsUpdated > 0 ? 1 : 0));
    }

    private Mono<ExternalJobSyncResult> finishRun(
            Long runId,
            List<ExternalJobSourceRecord> sources,
            List<SourceSyncResult> sourceResults) {
        int jobsSeen = sourceResults.stream().mapToInt(SourceSyncResult::jobsSeen).sum();
        int jobsUpserted = sourceResults.stream().mapToInt(SourceSyncResult::jobsUpserted).sum();
        String errorMessage = sourceResults.stream()
                .filter(SourceSyncResult::failed)
                .map(SourceSyncResult::summary)
                .collect(Collectors.joining("; "));
        String status = errorMessage.isBlank() ? "SUCCESS" : "PARTIAL_SUCCESS";

        return externalJobPostingStore.expireOldJobs(retentionDays)
                .flatMap(jobsExpired -> externalJobPostingStore.completeSyncRun(
                                runId,
                                status,
                                sources.size(),
                                jobsSeen,
                                jobsUpserted,
                                jobsExpired,
                                errorMessage.isBlank() ? null : errorMessage)
                        .thenReturn(new ExternalJobSyncResult(status, sources.size(), jobsSeen, jobsUpserted, jobsExpired)));
    }

    private Mono<Long> releaseLease() {
        return externalJobPostingStore.releaseSyncLease(SYNC_LOCK_NAME, syncOwnerId)
                .onErrorResume(error -> {
                    log.warn("Unable to release external job sync lease: {}", error.getMessage());
                    return Mono.just(0L);
                });
    }

    private record SourceSyncResult(
            ExternalJobSourceRecord source,
            int jobsSeen,
            int jobsUpserted,
            String errorMessage
    ) {
        boolean failed() {
            return errorMessage != null && !errorMessage.isBlank();
        }

        String summary() {
            return source.companyName() + " " + source.sourceType() + " " + source.boardToken() + ": " + errorMessage;
        }
    }
}
