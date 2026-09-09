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
    /**
     * Off by default. Retiring a posting is the only thing this sync does that a
     * later run cannot simply correct on its own -- a retirement that is never
     * reversed hardens into a hard delete after the purge window -- and the guard
     * that decides when it is safe was wrong once already. It stays off until a
     * run has been watched with the retired counts in the log.
     */
    private final boolean sweepEnabled;
    private final String syncOwnerId;

    /** Per-run ceiling on the catch-up pass, so it cannot dominate a sync. */
    private static final int PROSE_PAY_BACKFILL_LIMIT = 4000;

    public ExternalJobSyncService(
            ExternalJobPostingStore externalJobPostingStore,
            CandidateJobSearchService candidateJobSearchService,
            @Value("${airral.jobs.retention-days:60}") int retentionDays,
            @Value("${airral.jobs.purge-after-days:15}") int purgeAfterDays,
            @Value("${airral.jobs.sync.limit-per-source:500}") int limitPerSource,
            @Value("${airral.jobs.sync.lease-minutes:50}") int leaseMinutes,
            @Value("${airral.jobs.sync.source-concurrency:6}") int sourceConcurrency,
            @Value("${airral.jobs.sync.max-sources-per-run:500}") int maxSourcesPerRun,
            @Value("${airral.jobs.sync.sweep-enabled:false}") boolean sweepEnabled,
            @Value("${spring.application.name:airral-backend}") String applicationName) {
        this.externalJobPostingStore = externalJobPostingStore;
        this.candidateJobSearchService = candidateJobSearchService;
        this.retentionDays = Math.max(1, retentionDays);
        this.purgeAfterDays = Math.max(1, purgeAfterDays);
        this.limitPerSource = Math.max(1, Math.min(limitPerSource, 500));
        this.leaseMinutes = Math.max(5, leaseMinutes);
        this.sourceConcurrency = Math.max(1, Math.min(sourceConcurrency, 20));
        this.maxSourcesPerRun = Math.max(1, maxSourcesPerRun);
        this.sweepEnabled = sweepEnabled;
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

        return candidateJobSearchService.fetchForSync(
                        source.sourceType(),
                        source.boardToken(),
                        limitPerSource,
                        retentionDays)
                .flatMap(fetch -> upsertJobs(source, fetch.jobs())
                        .flatMap(jobsUpserted -> retireUnseenPostings(source, fetch, runStartedAt)
                                .flatMap(jobsRetired -> externalJobPostingStore.markSourceSuccess(source.id())
                                        .thenReturn(new SourceSyncResult(
                                                source, fetch.jobs().size(), jobsUpserted, jobsRetired, null, false)))))
                .onErrorResume(error -> {
                    String message = error.getMessage();
                    log.warn("External job sync failed for {} {}: {}", source.sourceType(), source.boardToken(), message);

                    // Auto-disable sources that return HTTP 404 (board no longer exists)
                    if (message != null && message.contains("(HTTP 404)")) {
                        log.info("Auto-disabling source {} {} because board returned 404", source.sourceType(), source.boardToken());
                        return externalJobPostingStore.disableSource(source.id(), message)
                                .then(externalJobPostingStore.deactivatePostingsForSource(source.id()))
                                .thenReturn(new SourceSyncResult(source, 0, 0, 0L, message, true));
                    }

                    return externalJobPostingStore.markSourceError(source.id(), message)
                            .thenReturn(new SourceSyncResult(source, 0, 0, 0L, message, false));
                });
    }

    /**
     * Retires postings the board no longer lists -- but only when this run can
     * prove it saw the whole board.
     *
     * <p>The first version of this compared the FILTERED result count against the
     * sync's own limit, and that was wrong in a way that disabled the guard
     * entirely on most sources. Two separate mistakes. The count was taken after
     * the country and dedupe filters, so a connector that returned a full page and
     * had rows thinned looked like one that had returned everything; and the
     * ceiling it compared against was the sync's limit of 500, which several
     * connectors never approach -- Lever caps its own page at 100, so the guard
     * could not fire for any Lever board, ever. The unit test pinned the arithmetic
     * rather than the plumbing and passed throughout.
     *
     * <p>It now compares the count BEFORE filtering against the ceiling that
     * particular connector can actually return, and fails closed: a source type
     * with no ceiling declared here is never swept, because an unknown ceiling
     * means an unprovable claim.
     */
    private Mono<Long> retireUnseenPostings(
            ExternalJobSourceRecord source,
            CandidateJobSearchService.SourceFetch fetch,
            OffsetDateTime runStartedAt) {
        if (!sweepEnabled) {
            return Mono.just(0L);
        }
        if (fetch.jobs().isEmpty()) {
            // A board with nothing open and a board that answered with nothing
            // useful look identical from here. Acting on the second would retire an
            // employer's whole listing off one bad response.
            return Mono.just(0L);
        }

        int ceiling = rawFetchCeiling(source.sourceType());
        if (ceiling <= 0 || fetch.rawCount() >= ceiling) {
            log.debug("Not sweeping {} {}: raw fetch {} against ceiling {} -- cannot prove the board was seen whole",
                    source.sourceType(), source.boardToken(), fetch.rawCount(), ceiling);
            return Mono.just(0L);
        }

        return externalJobPostingStore.deactivateUnseenPostings(source.id(), runStartedAt)
                .doOnNext(retired -> {
                    if (retired > 0) {
                        log.info("Retired {} posting(s) no longer listed by {} {} (saw {} of at most {})",
                                retired, source.sourceType(), source.boardToken(),
                                fetch.rawCount(), ceiling);
                    }
                });
    }

    /**
     * The most postings one fetch of this source can return, before filtering.
     *
     * <p>Returning fewer than this is the only evidence we have that a board was
     * exhausted rather than truncated. Zero means "not known", which disables the
     * sweep for that source -- deliberately, because guessing here retires live
     * jobs. Keep in step with the connectors: the value must match what the client
     * and its surrounding take() actually allow through.
     */
    private int rawFetchCeiling(String sourceType) {
        if (sourceType == null) {
            return 0;
        }
        return switch (sourceType.trim().toUpperCase(java.util.Locale.US)) {
            // greenhouseSummaries takes max(limit * 2, limit) from a response that
            // carries the whole board.
            case "GREENHOUSE" -> limitPerSource * 2;
            // These clients cap their own page at 100 regardless of what is asked.
            case "LEVER", "SMARTRECRUITERS" -> Math.min(limitPerSource, 100);
            // Paginate until exhausted or the limit, so the limit is the ceiling.
            case "WORKDAY", "ASHBY", "WORKABLE", "BAMBOOHR" -> limitPerSource;
            default -> 0;
        };
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
        // A board that 404s is gone and auto-disabling it is the correct outcome, so
        // it must not colour the run red forever. Anything else -- a timeout above
        // all -- is a board we still believe in that we failed to read, and it has to
        // be loud: nothing refreshes those postings, and they age out of the
        // catalogue about two weeks later with nothing in between to explain it.
        long unexplainedFailures = sourceResults.stream()
                .filter(SourceSyncResult::failed)
                .filter(result -> !result.autoDisabled())
                .count();
        String status = errorMessage.isBlank() ? "SUCCESS"
                : (unexplainedFailures > 0 ? "DEGRADED" : "PARTIAL_SUCCESS");
        if (unexplainedFailures > 0) {
            log.error("{} source(s) failed for a reason other than a dead board; their postings will not refresh",
                    unexplainedFailures);
        }

        // After expiry, so a retired posting is not counted as a live repost, and
        // after every source has landed, because the churn signal is only correct
        // once the run's whole picture is in.
        return externalJobPostingStore.recomputeJobQuality()
                .doOnNext(rescored -> {
                    if (rescored > 0) {
                        log.info("Rescored {} posting(s) on listing age and repost churn", rescored);
                    }
                })
                .then(backfillProsePayIntervals())
                .then(externalJobPostingStore.expireOldJobs(retentionDays))
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
            String errorMessage,
            boolean autoDisabled
    ) {
        boolean failed() {
            return errorMessage != null && !errorMessage.isBlank();
        }

        String summary() {
            return source.companyName() + " " + source.sourceType() + " " + source.boardToken() + ": " + errorMessage;
        }
    }
    /**
     * Puts the pay interval back on rows whose label predates the interval logic.
     *
     * <p>Workday, SmartRecruiters, Workable and the career pages state pay only in
     * their description, so their label is written by the detail cache rather than
     * by the sync -- and the detail endpoint answers from that cache. A row read
     * before the interval was understood therefore keeps a unit-less figure for
     * good: nothing recomputes it, and the sync writes "Salary not listed" for
     * these sources, which the upsert guard rightly refuses to overwrite the
     * stored label with. Without this pass an hourly warehouse wage goes on
     * reading like an annual salary on every card in the list.
     *
     * <p>Only rows that gain something are written. A row whose text states no
     * interval keeps its bare figure and is left alone rather than rewritten every
     * run, which also keeps this from churning HOT updates across the table.
     */
    private Mono<Long> backfillProsePayIntervals() {
        return externalJobPostingStore.findRowsMissingSalaryPeriod(PROSE_PAY_BACKFILL_LIMIT)
                .flatMap(row -> {
                    String[] pay = candidateJobSearchService.prosePayFrom(row.descriptionText());
                    if (pay == null || pay[1] == null) {
                        return Mono.just(0L);
                    }

                    return externalJobPostingStore.updateProsePay(row.id(), pay[0], pay[1]);
                }, 4)
                .reduce(0L, Long::sum)
                .doOnNext(updated -> {
                    if (updated > 0) {
                        log.info("Recovered the pay interval on {} posting(s) read before it was understood",
                                updated);
                    }
                });
    }

}
