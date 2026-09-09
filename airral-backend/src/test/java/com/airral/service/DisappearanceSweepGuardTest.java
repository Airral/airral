package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guards on retiring a posting because its board stopped listing it.
 *
 * <p>This is the only thing the sync does that a later run cannot always undo: a
 * retirement nobody reverses becomes a hard delete once the purge window passes.
 *
 * <p>The first version of these tests passed against a guard that could not fire
 * on most sources, which is worth saying plainly. It called the guard with a
 * number it had chosen itself and checked the comparison, so it verified the
 * arithmetic while the plumbing feeding that number was wrong twice over -- the
 * count arrived already filtered, and it was compared against a ceiling several
 * connectors never reach. These tests use the real per-connector ceilings and
 * assert on whether the store is asked at all, because being asked is the
 * destructive act.
 */
class DisappearanceSweepGuardTest {

    private static final int LIMIT_PER_SOURCE = 500;

    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class);

    /** The live sweep: enabled and not reporting. Every guard test below uses this. */
    private ExternalJobSyncService service(boolean sweepEnabled) {
        return service(sweepEnabled, false);
    }

    private ExternalJobSyncService service(boolean sweepEnabled, boolean sweepDryRun) {
        return new ExternalJobSyncService(
                store, mock(CandidateJobSearchService.class),
                60, 15, LIMIT_PER_SOURCE, 50, 6, 500, sweepEnabled, sweepDryRun, "airral-test");
    }

    private ExternalJobSourceRecord source(String sourceType) {
        return new ExternalJobSourceRecord(7L, 3L, "Acme", "acme.com", sourceType, "acme", sourceType);
    }

    /** One posting survives filtering; rawCount is what the connector produced. */
    private long retire(ExternalJobSyncService svc, String sourceType, int rawCount) {
        var fetch = new CandidateJobSearchService.SourceFetch(
                List.of(com.airral.dto.response.CandidateJobSummaryResponse.builder().build()), rawCount);
        Mono<Long> result = ReflectionTestUtils.invokeMethod(
                svc, "retireUnseenPostings", source(sourceType), fetch,
                OffsetDateTime.now(ZoneOffset.UTC));
        Long retired = result == null ? null : result.block();
        return retired == null ? -1 : retired;
    }

    @Test
    @DisplayName("the sweep is off unless someone turns it on")
    void offByDefault() {
        assertThat(retire(service(false), "GREENHOUSE", 10)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a board seen whole is swept")
    void completeFetchSweeps() {
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(4L));
        when(store.deactivateUnseenPostings(anyLong(), any())).thenReturn(Mono.just(4L));

        assertThat(retire(service(true), "GREENHOUSE", 120)).isEqualTo(4L);
        verify(store, times(1)).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("Lever returning its own full page is not a complete board")
    void leverPageCapIsRespected() {
        // The bug this whole guard exists for. Lever's client caps its page at 100,
        // so the old comparison against the sync's limit of 500 could never fire and
        // every Lever board was swept on a truncated response on every run.
        assertThat(retire(service(true), "LEVER", 100)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("SmartRecruiters shares the same 100-row page cap")
    void smartRecruitersPageCapIsRespected() {
        assertThat(retire(service(true), "SMARTRECRUITERS", 100)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a Greenhouse fetch at its own ceiling is not swept")
    void greenhouseCeilingIsRespected() {
        // greenhouseSummaries takes limit * 2, so that is the ceiling, not the limit.
        assertThat(retire(service(true), "GREENHOUSE", LIMIT_PER_SOURCE * 2)).isZero();
        assertThat(retire(service(true), "WORKDAY", LIMIT_PER_SOURCE)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("an unrecognised source is never swept")
    void unknownSourceFailsClosed() {
        // A ceiling we cannot state is a completeness claim we cannot make.
        assertThat(retire(service(true), "SOME_NEW_CONNECTOR", 3)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("an empty response is never read as an empty board")
    void emptyResponseDoesNotSweep() {
        var empty = new CandidateJobSearchService.SourceFetch(List.of(), 0);
        Mono<Long> result = ReflectionTestUtils.invokeMethod(
                service(true), "retireUnseenPostings", source("GREENHOUSE"), empty,
                OffsetDateTime.now(ZoneOffset.UTC));
        assertThat(result.block()).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("one posting short of a connector's ceiling is a complete board")
    void justUnderTheCeilingSweeps() {
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(1L));
        when(store.deactivateUnseenPostings(anyLong(), any())).thenReturn(Mono.just(1L));

        assertThat(retire(service(true), "LEVER", 99)).isEqualTo(1L);
        verify(store, times(1)).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a dry run reports what it would retire and writes nothing")
    void dryRunWritesNothing() {
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(9L));
        when(store.sampleUnseenPostings(anyLong(), any(), anyInt()))
                .thenReturn(reactor.core.publisher.Flux.just("Stale role (last seen ...) https://x"));

        // Returns 0 because nothing was retired, not because the guard declined.
        assertThat(retire(service(true, true), "GREENHOUSE", 120)).isZero();
        verify(store, times(1)).countUnseenPostings(anyLong(), any());
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a dry run still respects the guard, so the report cannot overstate")
    void dryRunHonoursTheCeiling() {
        // A Lever board at its own page cap is not provably whole, so a dry run must
        // stay silent about it rather than listing rows a live run would never touch.
        assertThat(retire(service(true, true), "LEVER", 100)).isZero();
        verify(store, never()).countUnseenPostings(anyLong(), any());
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("turning the sweep on alone does not retire anything")
    void enablingAloneIsNotEnough() {
        // The default for the dry-run flag is true, so sweep-enabled=true on its own
        // buys a report. Both flags have to be set before a row is retired.
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(3L));
        when(store.sampleUnseenPostings(anyLong(), any(), anyInt()))
                .thenReturn(reactor.core.publisher.Flux.empty());

        assertThat(retire(service(true, true), "GREENHOUSE", 50)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a board is never swept down by half, however whole it looked")
    void refusesToRetireHalfOfWhatItSaw() {
        // The real case, from the first dry run against production. Target's Workday
        // board returned 499 rows against a declared ceiling of 500, so the ceiling
        // test passed it by one row -- and the sweep then wanted to retire 597
        // postings, more than the run had just seen. Target has far more than 499
        // jobs; the paginator had stopped early and the ceiling could not tell.
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(597L));

        assertThat(retire(service(true), "WORKDAY", 499)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("ordinary churn is still swept")
    void ordinaryChurnStillSweeps() {
        // Every board genuinely seen whole in that same run wanted between 10% and
        // 36% of what it saw. This is the top of that range.
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(37L));
        when(store.deactivateUnseenPostings(anyLong(), any())).thenReturn(Mono.just(37L));

        assertThat(retire(service(true), "GREENHOUSE", 104)).isEqualTo(37L);
        verify(store, times(1)).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("the half rule is applied to the dry run too, so a report cannot promise a sweep that would refuse")
    void dryRunHonoursTheHalfRule() {
        when(store.countUnseenPostings(anyLong(), any())).thenReturn(Mono.just(597L));

        assertThat(retire(service(true, true), "WORKDAY", 499)).isZero();
        verify(store, never()).sampleUnseenPostings(anyLong(), any(), anyInt());
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

}
