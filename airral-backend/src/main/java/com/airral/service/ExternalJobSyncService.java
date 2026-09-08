package com.airral.service;

import com.airral.dto.response.CandidateJobSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private final int purgeAfterDays;
    private final int limitPerSource;
    private final int leaseMinutes;
    private final int sourceConcurrency;
    private final int maxSourcesPerRun;
    private final String syncOwnerId;

    public ExternalJobSyncService(
            ExternalJobPostingStore externalJobPostingStore,
            CandidateJobSearchService candidateJobSearchService,
            @Value("${airral.jobs.retention-days:60}") int retentionDays,
            @Value("${airral.jobs.purge-after-days:15}") int purgeAfterDays,
            @Value("${airral.jobs.sync.limit-per-source:500}") int limitPerSource,
            @Value("${airral.jobs.sync.lease-minutes:50}") int leaseMinutes,
            @Value("${airral.jobs.sync.source-concurrency:6}") int sourceConcurrency,
            @Value("${airral.jobs.sync.max-sources-per-run:500}") int maxSourcesPerRun,
            @Value("${spring.application.name:airral-backend}") String applicationName) {
        this.externalJobPostingStore = externalJobPostingStore;
        this.candidateJobSearchService = candidateJobSearchService;
        this.retentionDays = Math.max(1, retentionDays);
        this.purgeAfterDays = Math.max(1, purgeAfterDays);
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
                        return Mono.just(new ExternalJobSyncResult("SKIPPED_LOCKED", 0, 0, 0, 0, 0, 0));
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
        // Taken before the fetch, so every posting this run touches ends up with a
        // last_seen_at after it and anything left behind is provably absent from
        // what the board just returned.
        OffsetDateTime runStartedAt = OffsetDateTime.now(ZoneOffset.UTC);

        return candidateJobSearchService.getLiveRecommendedJobsForSync(
                        source.sourceType(),
                        source.boardToken(),
                        limitPerSource,
                        retentionDays)
                .collectList()
                .flatMap(jobs -> upsertJobs(source, jobs)
                        .flatMap(jobsUpserted -> retireUnseenPostings(source, jobs.size(), runStartedAt)
                                .flatMap(jobsRetired -> externalJobPostingStore.markSourceSuccess(source.id())
                                        .thenReturn(new SourceSyncResult(
                                                source, jobs.size(), jobsUpserted, jobsRetired, null)))))
                .onErrorResume(error -> {
                    String message = error.getMessage();
                    log.warn("External job sync failed for {} {}: {}", source.sourceType(), source.boardToken(), message);

                    // Auto-disable sources that return HTTP 404 (board no longer exists)
                    if (message != null && message.contains("(HTTP 404)")) {
                        log.info("Auto-disabling source {} {} because board returned 404", source.sourceType(), source.boardToken());
                        return externalJobPostingStore.disableSource(source.id(), message)
                                .then(externalJobPostingStore.deactivatePostingsForSource(source.id()))
                                .thenReturn(new SourceSyncResult(source, 0, 0, 0L, message));
                    }

                    return externalJobPostingStore.markSourceError(source.id(), message)
                            .thenReturn(new SourceSyncResult(source, 0, 0, 0L, message));
                });
    }

    /**
     * Retires postings the board no longer lists -- but only when this run can
     * actually tell the difference.
     *
     * <p>"Absent from the response" and "absent from the board" are the same
     * thing only when the response was complete, and two cases break that.
     *
     * <p>An empty result is ambiguous. A board with no open roles and a board
     * that answered 200 with nothing useful look identical from here, and acting
     * on the second would retire an employer's entire listing on one bad
     * response. The retention window handles a board that has genuinely emptied,
     * a few days later.
     *
     * <p>A full page is worse, because it looks healthy. The fetch stops at
     * limitPerSource, so on a board with more postings than that the ones we
     * never asked for are indistinguishable from the ones taken down -- a naive
     * sweep would retire real jobs on every run and resurrect them on the next,
     * churning the largest employers hardest. Those boards keep the old
     * behaviour until the fetch is paginated.
     */
    private Mono<Long> retireUnseenPostings(
            ExternalJobSourceRecord source, int jobsSeen, OffsetDateTime runStartedAt) {
        if (jobsSeen == 0) {
            return Mono.just(0L);
        }
        if (jobsSeen >= limitPerSource) {
            log.debug("Not sweeping {} {}: fetch hit the {}-posting limit, so absence is not evidence",
                    source.sourceType(), source.boardToken(), limitPerSource);
            return Mono.just(0L);
        }

        return externalJobPostingStore.deactivateUnseenPostings(source.id(), runStartedAt)
                .doOnNext(retired -> {
                    if (retired > 0) {
                        log.info("Retired {} posting(s) no longer listed by {} {}",
                                retired, source.sourceType(), source.boardToken());
                    }
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
        long jobsRetired = sourceResults.stream().mapToLong(SourceSyncResult::jobsRetired).sum();
        String errorMessage = sourceResults.stream()
                .filter(SourceSyncResult::failed)
                .map(SourceSyncResult::summary)
                .collect(Collectors.joining("; "));
        String status = errorMessage.isBlank() ? "SUCCESS" : "PARTIAL_SUCCESS";

        return externalJobPostingStore.expireOldJobs(retentionDays)
                .flatMap(jobsExpired -> externalJobPostingStore.purgeExpiredJobs(purgeAfterDays)
                        .flatMap(jobsPurged -> externalJobPostingStore.completeSyncRun(
                                        runId,
                                        status,
                                        sources.size(),
                                        jobsSeen,
                                        jobsUpserted,
                                        jobsExpired,
                                        errorMessage.isBlank() ? null : errorMessage)
                                .thenReturn(new ExternalJobSyncResult(
                                        status, sources.size(), jobsSeen, jobsUpserted,
                                        jobsRetired, jobsExpired, jobsPurged))));
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
            long jobsRetired,
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
