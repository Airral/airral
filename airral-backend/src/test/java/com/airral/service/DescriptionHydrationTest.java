package com.airral.service;

import com.airral.dto.response.CandidateJobDetailResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pass that fetches bodies for the boards that ship none.
 *
 * <p>Workday and SmartRecruiters put no description in their list payload, so
 * every column derived from text -- sponsorship above all, at 86% UNKNOWN over
 * a 200-posting live sample -- stayed empty for about a third of the
 * catalogue. Workday alone is roughly 28% of it. (Workable had the same
 * symptom for a different reason and is fixed in toWorkableSummary instead:
 * its list call already carries the body, and its detail fetch re-downloads
 * the whole board, so it must never appear in this pass's source list.)
 *
 * <p>The thing these tests exist to stop is the version that appears to work.
 * getExternalJobDetail is cache-first, and for exactly these rows the cache
 * answers: a Workday posting already has a title, a location and an apply URL,
 * so findCachedJobDetail returns it and the live fetch never runs. A pass built
 * on it would log a healthy count every four hours and change nothing. That
 * mistake has already been made once in this project, on a pay-interval fix, so
 * the first test below asserts on which entry point is called rather than on
 * the count that comes out.
 */
class DescriptionHydrationTest {

    private static final List<String> SOURCES = List.of("WORKDAY", "SMARTRECRUITERS");

    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class);
    private final CandidateJobSearchService search = mock(CandidateJobSearchService.class);

    private ExternalJobSyncService service(boolean hydrationEnabled, int limitPerRun) {
        return new ExternalJobSyncService(
                store, search,
                60, 15, 500, 50, 6, 500,
                // Sweep off. Retiring and hydrating must not be able to reach
                // each other, and a test that needed both on would say otherwise.
                false, true,
                hydrationEnabled, limitPerRun, 40, 4,
                // Matches the shipped default. Workable is not in it: its list
                // call already carries the body, and its detail fetch is a
                // whole-board download, so hydrating it would be one full board
                // per posting.
                "WORKDAY,SMARTRECRUITERS",
                "airral-test");
    }

    private long hydrate(ExternalJobSyncService svc) {
        Mono<Long> result = ReflectionTestUtils.invokeMethod(svc, "hydrateMissingDescriptions");
        Long hydrated = result == null ? null : result.block();
        return hydrated == null ? -1 : hydrated;
    }

    private ExternalJobPostingStore.MissingBodyRow row(long id, String board) {
        return new ExternalJobPostingStore.MissingBodyRow(id, "WORKDAY", board, "req-" + id);
    }

    /** A detail response the way loadExternalJobDetail hands one back. */
    private CandidateJobDetailResponse detailWithBody() {
        return CandidateJobDetailResponse.builder()
                .sourceType("WORKDAY")
                .sourceBoardToken("acme")
                .externalJobId("req-1")
                .descriptionHtml("<p>You will do the thing.</p>")
                .descriptionText("You will do the thing.")
                .build();
    }

    private void backlog(long total, List<ExternalJobPostingStore.MissingBodyRow> work) {
        when(store.countRowsMissingDescription(anyList())).thenReturn(Mono.just(total));
        when(store.findRowsMissingDescription(anyList(), anyInt(), anyInt()))
                .thenReturn(Flux.fromIterable(work));
    }

    @Test
    @DisplayName("the pass is off when it is disabled, and asks the database nothing")
    void disabledDoesNothing() {
        assertThat(hydrate(service(false, 300))).isZero();

        verify(store, never()).countRowsMissingDescription(anyList());
        verify(store, never()).findRowsMissingDescription(anyList(), anyInt(), anyInt());
        verify(search, never()).fetchLiveJobDetail(anyString(), anyString(), anyString());
        verify(store, never()).cacheJobDetail(any());
    }

    @Test
    @DisplayName("the live loader is driven, never the cache-first detail endpoint")
    void bypassesTheCacheFirstPath() {
        // The whole reason this pass has its own entry point. getExternalJobDetail
        // would find the row it is trying to fill already cached and return it
        // without fetching anything.
        backlog(1, List.of(row(1L, "acme")));
        when(search.fetchLiveJobDetail(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        assertThat(hydrate(service(true, 300))).isEqualTo(1L);

        verify(search, times(1)).fetchLiveJobDetail("WORKDAY", "acme", "req-1");
        verify(search, never()).getExternalJobDetail(anyString(), anyString(), anyString());
        verify(store, never()).findCachedJobDetail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("only rows with no body are work, and the per-run cap is what is asked for")
    void asksOnlyForRowsMissingABody() {
        // The predicate is the thing that stops a hydrated posting being fetched
        // again next run, and it is SQL, so it is pinned as SQL. Both description
        // columns have to be empty: description_text alone would keep re-reading
        // Greenhouse rows that arrived with a body, and description_html alone is
        // the column a successful hydration fills, so the pass would spend its
        // whole budget re-reading its own work.
        assertThat(ExternalJobPostingStore.MISSING_BODY_PREDICATE)
                .contains("NULLIF(description_text, '') IS NULL")
                .contains("NULLIF(description_html, '') IS NULL")
                .contains("is_active = true");

        backlog(0, List.of());

        assertThat(hydrate(service(true, 300))).isZero();
        verify(search, never()).fetchLiveJobDetail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the per-run cap is the number the work list is asked for, and the whole of it is worked")
    void respectsThePerRunCap() {
        List<ExternalJobPostingStore.MissingBodyRow> work = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> row(i, "board-" + i))
                .toList();
        // Backlog far larger than the cap: this is the draining case, not the
        // drained one.
        backlog(4300, work);
        when(search.fetchLiveJobDetail(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        assertThat(hydrate(service(true, 5))).isEqualTo(5L);

        verify(store, times(1)).findRowsMissingDescription(SOURCES, 40, 5);
        verify(search, times(5)).fetchLiveJobDetail(anyString(), anyString(), anyString());
        verify(store, times(5)).cacheJobDetail(any());
    }

    @Test
    @DisplayName("one posting failing does not cost the ones after it")
    void oneFailureDoesNotAbortTheRest() {
        backlog(3, List.of(row(1L, "alpha"), row(2L, "beta"), row(3L, "gamma")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("alpha"), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("beta"), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("board returned 403")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("gamma"), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        // Two hydrated, and the pass itself completed rather than erroring.
        assertThat(hydrate(service(true, 300))).isEqualTo(2L);
        verify(store, times(2)).cacheJobDetail(any());
    }

    @Test
    @DisplayName("a response with no body is not written back over the row")
    void writesNothingWhenNothingWasGained() {
        // cacheJobDetail rewrites the whole derived block rather than merging it,
        // so persisting a bodyless response would blank the columns this pass
        // exists to fill.
        backlog(1, List.of(row(1L, "acme")));
        when(search.fetchLiveJobDetail(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(CandidateJobDetailResponse.builder()
                        .sourceType("WORKDAY")
                        .sourceBoardToken("acme")
                        .externalJobId("req-1")
                        .build()));

        assertThat(hydrate(service(true, 300))).isZero();
        verify(store, never()).cacheJobDetail(any());
    }

    @Test
    @DisplayName("a board that always fails stops after three tries and does not starve the others")
    void aDeadBoardGivesUpItsSlice() {
        // Lowe's answers 403 to this service on every request, so a board that
        // can never succeed is a present case. Ordering the work list oldest-first
        // would otherwise hand it the same rows every run, forever.
        List<ExternalJobPostingStore.MissingBodyRow> dead = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> row(i, "lowes"))
                .toList();
        List<ExternalJobPostingStore.MissingBodyRow> live = IntStream.rangeClosed(21, 24)
                .mapToObj(i -> row(i, "acme"))
                .toList();
        backlog(24, java.util.stream.Stream.concat(dead.stream(), live.stream()).toList());

        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("lowes"), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("Forbidden (HTTP 403)")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        assertThat(hydrate(service(true, 300))).isEqualTo(4L);

        // Three attempts, not twenty. The other seventeen are left for a run in
        // which the board might answer, and the budget went to the board that did.
        verify(search, times(3)).fetchLiveJobDetail(eq("WORKDAY"), eq("lowes"), anyString());
        verify(search, times(4)).fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), anyString());
    }

    @Test
    @DisplayName("a board recovering mid-slice is not given up on")
    void theBreakerCountsConsecutiveFailuresOnly() {
        // Two failures then a success must not leave the board one bad posting
        // away from being dropped for the rest of the run.
        backlog(6, IntStream.rangeClosed(1, 6).mapToObj(i -> row(i, "acme")).toList());
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-1")))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-2")))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-3")))
                .thenReturn(Mono.just(detailWithBody()));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-4")))
                .thenReturn(Mono.error(new IllegalStateException("timeout")));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-5")))
                .thenReturn(Mono.just(detailWithBody()));
        when(search.fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), eq("req-6")))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        assertThat(hydrate(service(true, 300))).isEqualTo(3L);
        verify(search, times(6)).fetchLiveJobDetail(eq("WORKDAY"), eq("acme"), anyString());
    }

    @Test
    @DisplayName("the pass cannot change what the disappearance sweep sees or retires")
    void cannotDisturbTheSweep() {
        // The single most dangerous interaction. The sweep decides what to retire
        // from last_seen_at against the timestamp taken before each source's
        // fetch, and cacheJobDetail is the only write this pass makes -- it sets
        // no last_seen_at, no is_active and no job_source_id. This pins the
        // negative: hydration touches none of the sweep's inputs, and does not
        // upsert, which is the one call that would move last_seen_at.
        backlog(2, List.of(row(1L, "acme"), row(2L, "beta")));
        when(search.fetchLiveJobDetail(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(detailWithBody()));
        when(store.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        assertThat(hydrate(service(true, 300))).isEqualTo(2L);

        verify(store, never()).upsertJob(any(), any(), anyInt());
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
        verify(store, never()).countUnseenPostings(anyLong(), any());
        verify(store, never()).deactivatePostingsForSource(anyLong());
    }

    @Test
    @DisplayName("a work list the pass cannot even read does not fail the run")
    void aBrokenWorkListDoesNotFailTheRun() {
        when(store.countRowsMissingDescription(anyList()))
                .thenReturn(Mono.error(new IllegalStateException("connection pool exhausted")));

        assertThat(hydrate(service(true, 300))).isZero();
    }
}
