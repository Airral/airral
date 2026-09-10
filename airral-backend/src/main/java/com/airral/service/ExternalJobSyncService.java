package com.airral.service;

import com.airral.dto.response.CandidateJobSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    /**
     * Reports what the sweep would retire instead of retiring it.
     *
     * <p>Defaults to true, so turning sweep-enabled on gets you a report and not
     * a retirement. Retiring is the only irreversible thing this pipeline does --
     * an unreversed retirement hardens into a hard delete once the purge window
     * passes -- and the guard that decides when it is safe was wrong once already:
     * it compared a post-filter count against a ceiling several connectors never
     * reach, so it could not fire for any Lever board, ever. Both flags have to be
     * set deliberately before a row is retired, and the dry run is how you find
     * out what the second one will do.
     */
    private final boolean sweepDryRun;
    private final String syncOwnerId;

    /** How many would-be retirements a dry run names, per source. */
    private static final int SWEEP_SAMPLE_SIZE = 5;

    /** Per-run ceiling on the catch-up pass, so it cannot dominate a sync. */
    private static final int PROSE_PAY_BACKFILL_LIMIT = 4000;

    /**
     * Unproductive attempts in a row that take a board out of the hydration pass
     * for the rest of the run.
     *
     * <p>Not hypothetical: one board in the catalogue answers 403 to this service
     * on every request. Without this it would spend its whole per-board slice on
     * every run, forever, on rows that can never be filled. Three is enough to
     * tell a board that is down from a posting that happens to have been pulled
     * between the list fetch and now.
     */
    private static final int HYDRATION_BOARD_FAILURE_LIMIT = 3;

    /**
     * Wall clock the pass will not start new work past.
     *
     * <p>The per-request ceiling is not the 8s default. application-sync.yml sets
     * airral.jobs.source-timeout-seconds to 45 for exactly this run, because the
     * batch has no user waiting on it -- so the arithmetic worst case for a
     * 400-posting budget is hours, not the thirteen minutes an 8s timeout would
     * give, and it lands past both the 50-minute lease and the workflow's
     * 60-minute timeout, which also has to cover a Gradle build.
     *
     * <p>Nothing else bounds it. The failure breaker only bounds a board that is
     * failing; a board that is merely slow and succeeding is unbounded, and the
     * per-board work is serial, so a single backlogged board gets no help from
     * the concurrency either. Ten minutes leaves the rest of the run its usual
     * four and the lease most of its margin. Whatever is not reached is simply
     * the head of the next run's work list, four hours later.
     */
    private static final Duration HYDRATION_BUDGET = Duration.ofMinutes(10);

    /** Sources whose list payload carries no body, so nothing text-derived is filled. */
    private final List<String> hydrationSources;
    private final boolean hydrationEnabled;
    private final int hydrationLimitPerRun;
    private final int hydrationLimitPerBoard;
    private final int hydrationConcurrency;

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
            @Value("${airral.jobs.sync.sweep-dry-run:true}") boolean sweepDryRun,
            @Value("${airral.jobs.sync.hydrate-descriptions.enabled:true}") boolean hydrationEnabled,
            @Value("${airral.jobs.sync.hydrate-descriptions.limit-per-run:400}") int hydrationLimitPerRun,
            @Value("${airral.jobs.sync.hydrate-descriptions.limit-per-board:200}") int hydrationLimitPerBoard,
            @Value("${airral.jobs.sync.hydrate-descriptions.concurrency:4}") int hydrationConcurrency,
            @Value("${airral.jobs.sync.hydrate-descriptions.sources:WORKDAY,SMARTRECRUITERS}")
                    String hydrationSources,
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
        this.sweepDryRun = sweepDryRun;
        this.hydrationEnabled = hydrationEnabled;
        // Capped, not just floored. These are requests to other companies'
        // servers inside a 50-minute lease, so a fat-fingered override must not
        // be able to turn the pass into a crawl or the run into a lease overrun.
        this.hydrationLimitPerRun = Math.max(0, Math.min(hydrationLimitPerRun, 2000));
        this.hydrationLimitPerBoard = Math.max(1, Math.min(hydrationLimitPerBoard, 2000));
        this.hydrationConcurrency = Math.max(1, Math.min(hydrationConcurrency, 8));
        this.hydrationSources = parseSources(hydrationSources);
        this.syncOwnerId = applicationName + "-" + UUID.randomUUID();
    }

    private static List<String> parseSources(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.US))
                .distinct()
                .toList();
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

        // Second guard, and the one a real dry run earned. The ceiling test asks
        // whether the connector could have been truncated; this asks whether the
        // numbers make sense afterwards. Target's Workday board came back with 499
        // rows against a declared ceiling of 500 -- one short, so the ceiling test
        // passed it -- and the sweep would then have retired 597 postings, more
        // than the run had just seen. Target plainly has more than 499 jobs; the
        // paginator had stopped early and the ceiling could not tell.
        //
        // Across the same run, every board that was genuinely seen whole wanted to
        // retire between 10% and 36% of what it saw. Half is therefore far outside
        // ordinary churn, and a board that really did shed half its listings in one
        // cycle is exactly the case that should stop and be looked at rather than
        // be actioned automatically. Fails closed, and says so.
        return externalJobPostingStore.countUnseenPostings(source.id(), runStartedAt)
                .flatMap(wouldRetire -> {
                    if (wouldRetire == 0) {
                        return Mono.just(0L);
                    }

                    if (wouldRetire * 2 >= fetch.rawCount()) {
                        log.warn("Not sweeping {} {}: would retire {} of the {} seen. A board does not "
                                        + "usually shed half its listings at once, so this reads as a "
                                        + "partial fetch rather than a disappearance.",
                                source.sourceType(), source.boardToken(), wouldRetire, fetch.rawCount());
                        return Mono.just(0L);
                    }

                    return finishSweep(source, fetch, runStartedAt, ceiling, wouldRetire);
                });
    }

    private Mono<Long> finishSweep(
            ExternalJobSourceRecord source,
            CandidateJobSearchService.SourceFetch fetch,
            OffsetDateTime runStartedAt,
            int ceiling,
            long wouldRetire) {
        if (sweepDryRun) {
            return externalJobPostingStore
                    .sampleUnseenPostings(source.id(), runStartedAt, SWEEP_SAMPLE_SIZE)
                    .collectList()
                    .doOnNext(sample -> {
                        log.warn("SWEEP DRY RUN: would retire {} posting(s) from {} {} "
                                        + "(saw {} of at most {}). Nothing was written.",
                                wouldRetire, source.sourceType(), source.boardToken(),
                                fetch.rawCount(), ceiling);
                        sample.forEach(row -> log.warn("SWEEP DRY RUN:   {}", row));
                    })
                    .thenReturn(0L);
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

        // Hydration first, and specifically before the quality rescore and the
        // pay backfill: both of those read columns this pass writes, so running
        // it first means a posting hydrated this run is scored and has its pay
        // interval recovered this run rather than in four hours' time.
        //
        // After expiry, so a retired posting is not counted as a live repost, and
        // after every source has landed, because the churn signal is only correct
        // once the run's whole picture is in.
        return hydrateMissingDescriptions()
                .then(externalJobPostingStore.recomputeJobQuality())
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

    /**
     * Fetches bodies for the boards that ship none in their list payload.
     *
     * <p>Greenhouse, Lever and Ashby put the posting body in the list response,
     * so the sync derives sponsorship, visa signals, seniority, experience years
     * and prose pay for free. Workday and SmartRecruiters do not, and the sync
     * never asked for one, so their mappers set no descriptionText and every
     * text-derived column stayed empty. Measured over 200 live postings, 86% had
     * sponsorship UNKNOWN; Workday alone is about 28% of the catalogue.
     * Sponsorship is one of the two filters a candidate reaches for first, and it
     * was blank on roughly a third of the jobs we show.
     *
     * <p>Workable is deliberately NOT hydrated here even though it had the same
     * symptom. Its list call is already made with details=true, so its body was
     * always in the payload the sync downloads -- toWorkableSummary just did not
     * put it on the summary, and now does. Hydrating it instead would have been
     * catastrophic rather than merely wasteful: getWorkableJobDetail is not a
     * per-posting endpoint, it re-fetches the entire board and filters to one
     * job, so a board with 200 bodyless rows would have meant 200 whole-board
     * downloads per run. Any source added to this list has to be checked the
     * same way -- Ashby, BambooHR and the career pages resolve their detail from
     * a list fetch too.
     *
     * <p>Work mode is NOT one of the columns this fixes, which is worth saying
     * plainly because it is the other of those two filters. cacheJobDetail does
     * not write work_mode at all, and inferWorkMode reads only the title and the
     * location -- never the body -- so for these sources the detail response's
     * work mode is derived from exactly the same inputs the list response
     * already gave us. Closing the 74% UNKNOWN there needs the
     * derivation itself to start reading the description, which is a separate
     * change and a second derivation path this pass deliberately does not open.
     *
     * <p>Nothing here derives anything. loadExternalJobDetail already produces the
     * fully derived response and cacheJobDetail already persists it, both of them
     * exercised on every cold detail view, so this pass is a work list, a budget
     * and a failure policy around those two.
     *
     * <p>attachCompanyBrand is deliberately not called. It reads the company row
     * to fill companyName, domain and logo onto the response, and cacheJobDetail
     * writes none of those three columns -- so on a write-only pass it would be
     * one extra query per posting on a db-f1-micro for a value nothing reads. The
     * detail endpoint still calls it, because there the response is rendered.
     */
    private Mono<Long> hydrateMissingDescriptions() {
        if (!hydrationEnabled || hydrationSources.isEmpty() || hydrationLimitPerRun == 0) {
            return Mono.just(0L);
        }

        return externalJobPostingStore.countRowsMissingDescription(hydrationSources)
                .flatMap(backlog -> backlog == 0
                        ? Mono.just(0L)
                        : externalJobPostingStore
                                .findRowsMissingDescription(
                                        hydrationSources, hydrationLimitPerBoard, hydrationLimitPerRun)
                                .collectList()
                                .flatMap(rows -> hydrateRows(rows, backlog)))
                .onErrorResume(error -> {
                    // The catalogue is already usable without this. A pass that
                    // cannot read its own work list must not take the run down
                    // with it and leave every source unmarked.
                    log.warn("Description hydration pass did not run: {}", error.toString());
                    return Mono.just(0L);
                });
    }

    private Mono<Long> hydrateRows(List<ExternalJobPostingStore.MissingBodyRow> rows, long backlog) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }

        // Grouped here rather than with Flux.groupBy, which only works while every
        // group is being drained: with flatMap bounded to a handful of boards at a
        // time, the groups past that bound are never subscribed and the pass
        // stalls until the lease runs out. The work list is already bounded and
        // collected, so a plain map is both simpler and safe.
        Map<String, List<ExternalJobPostingStore.MissingBodyRow>> byBoard = new LinkedHashMap<>();
        rows.forEach(row -> byBoard
                .computeIfAbsent(row.sourceType() + " " + row.boardToken(), key -> new ArrayList<>())
                .add(row));

        AtomicLong hydrated = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        // Taken once for the whole pass rather than per board, so four slow
        // boards running side by side share one deadline instead of each getting
        // its own and the run paying for the slowest of them four times over.
        Instant deadline = Instant.now().plus(HYDRATION_BUDGET);

        return Flux.fromIterable(byBoard.entrySet())
                .flatMap(board -> hydrateBoard(board.getKey(), board.getValue(), deadline, hydrated, failed),
                        hydrationConcurrency)
                .then(Mono.fromSupplier(() -> {
                    long gained = hydrated.get();
                    log.info("Hydrated {} description(s), {} failed, {} of {} still without a body "
                                    + "across {} board(s)",
                            gained, failed.get(), Math.max(0L, backlog - gained), backlog, byBoard.size());
                    return gained;
                }));
    }

    /**
     * One board's slice, worked one posting at a time.
     *
     * <p>Serial within a board and concurrent across boards. That is the polite
     * shape -- one in-flight request per employer -- and it is also what gives the
     * breaker something to count: a board is only provably down if its failures
     * are consecutive, which requires an order.
     */
    private Mono<Void> hydrateBoard(
            String board,
            List<ExternalJobPostingStore.MissingBodyRow> boardRows,
            Instant deadline,
            AtomicLong hydrated,
            AtomicLong failed) {
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicInteger attempted = new AtomicInteger();

        // Checked before starting a request rather than as a timeout around the
        // pass: abandoning a request mid-flight would leave the write it was
        // about to make half-decided, and there is nothing to gain from that
        // when the row is simply picked up again next run.
        return Flux.fromIterable(boardRows)
                .concatMap(row -> consecutiveFailures.get() >= HYDRATION_BOARD_FAILURE_LIMIT
                                || Instant.now().isAfter(deadline)
                        ? Mono.<Boolean>empty()
                        : hydrateOne(row).doOnNext(gained -> {
                            attempted.incrementAndGet();
                            if (gained) {
                                hydrated.incrementAndGet();
                                consecutiveFailures.set(0);
                            } else {
                                failed.incrementAndGet();
                                consecutiveFailures.incrementAndGet();
                            }
                        }))
                .then(Mono.<Void>fromRunnable(() -> {
                    int skipped = boardRows.size() - attempted.get();
                    if (skipped <= 0) {
                        return;
                    }

                    if (consecutiveFailures.get() >= HYDRATION_BOARD_FAILURE_LIMIT) {
                        log.warn("Hydration gave up on {} after {} failures in a row; the {} posting(s) "
                                        + "left in its slice were skipped so the budget goes to boards "
                                        + "that answer",
                                board, HYDRATION_BOARD_FAILURE_LIMIT, skipped);
                    } else {
                        log.warn("Hydration stopped at its {} wall clock with {} posting(s) of {} "
                                        + "unfetched; they are the head of the next run's work list",
                                HYDRATION_BUDGET, skipped, board);
                    }
                }));
    }

    /**
     * One posting. Never fails, never writes something worse than it found.
     *
     * @return true when the posting gained a body, false for anything else --
     *         board error, a posting the board no longer serves, or a response
     *         that came back without a description.
     */
    private Mono<Boolean> hydrateOne(ExternalJobPostingStore.MissingBodyRow row) {
        return candidateJobSearchService
                // The live loader, not getExternalJobDetail. That one is
                // cache-first, and for these rows the cache answers -- a Workday
                // posting already has a title, a location and an apply URL, so
                // findCachedJobDetail returns it and no fetch ever happens.
                .fetchLiveJobDetail(row.sourceType(), row.boardToken(), row.externalJobId())
                // Only write what gains something. cacheJobDetail rewrites the
                // whole derived block rather than merging it, so persisting a
                // bodyless response would blank the columns this pass exists to
                // fill and leave the row worse than it found it.
                .filter(detail -> hasText(detail.getDescriptionHtml()) || hasText(detail.getDescriptionText()))
                .flatMap(externalJobPostingStore::cacheJobDetail)
                .map(updated -> updated > 0)
                .defaultIfEmpty(false)
                .onErrorResume(error -> {
                    // Debug, not warn. A board that is properly down says so once
                    // per board in hydrateBoard; one line per posting would bury
                    // the sync's own errors under a few hundred of these.
                    log.debug("Hydration failed for {} {} {}: {}",
                            row.sourceType(), row.boardToken(), row.externalJobId(), error.toString());
                    return Mono.just(false);
                });
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
