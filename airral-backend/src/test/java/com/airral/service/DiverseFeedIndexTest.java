package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The rules that keep the diversified feed from ever being worse than the plain
 * one: it is built in the background, an expired list is still served while a
 * new one is built, one build runs at a time, and a failure waits a minute.
 */
class DiverseFeedIndexTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private ExternalJobPostingStore store;
    private TestClock clock;
    private DiverseFeedIndex index;

    @BeforeEach
    void setUp() {
        store = mock(ExternalJobPostingStore.class);
        clock = new TestClock();
        index = new DiverseFeedIndex(store, clock);
    }

    private void builds(List<Long> ids) {
        when(store.findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.just(ids));
    }

    private DiverseFeedIndex.Feed lookup() {
        return index.idsOrNull("all", null, 60, ExplicitJobFilters.none());
    }

    /** The build runs on another thread; wait for it rather than sleep a fixed time. */
    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 seconds");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @Test
    @DisplayName("with no list yet the caller gets null, so the ordinary feed is served, and a build starts")
    void nothingReadyFallsBackAndBuilds() {
        builds(List.of(5L, 3L, 9L));

        assertThat(lookup()).as("never makes a visitor wait for the build").isNull();

        eventually(() -> lookup() != null);
        assertThat(lookup().ids()).containsExactly(5L, 3L, 9L);
        assertThat(lookup().complete()).as("fewer than the cap means the list is the whole feed").isTrue();
    }

    @Test
    @DisplayName("a list as long as the cap may be a truncation, and says so")
    void fullListIsNotComplete() {
        List<Long> full = new ArrayList<>();
        for (long id = 1; id <= DiverseFeedIndex.MAX_IDS; id++) {
            full.add(id);
        }
        builds(full);

        lookup();
        eventually(() -> lookup() != null);

        assertThat(lookup().complete()).isFalse();
    }

    @Test
    @DisplayName("an expired list is still served while a fresh one is built")
    void staleIsServedWhileRefreshing() {
        builds(List.of(1L, 2L));
        lookup();
        eventually(() -> lookup() != null);

        builds(List.of(7L, 8L));
        clock.advance(DiverseFeedIndex.TTL.plusSeconds(1));

        assertThat(lookup().ids()).as("stale, not null: the visitor does not wait").containsExactly(1L, 2L);
        eventually(() -> lookup().ids().contains(7L));
        assertThat(lookup().ids()).containsExactly(7L, 8L);
    }

    @Test
    @DisplayName("a fresh list is not rebuilt")
    void freshListIsKept() {
        builds(List.of(1L));
        lookup();
        eventually(() -> lookup() != null);

        clock.advance(DiverseFeedIndex.TTL.minusMinutes(1));
        lookup();
        lookup();

        verify(store, times(1)).findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("only one build runs at a time, for the whole process")
    void oneBuildAtATime() {
        Sinks.One<List<Long>> slow = Sinks.one();
        when(store.findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(slow.asMono());

        lookup();
        // A different filter combination while the first is still building.
        index.idsOrNull("all", null, 60, new ExplicitJobFilters("REMOTE", false, null, false));
        index.idsOrNull("all", null, 1, ExplicitJobFilters.none());

        verify(store, times(1)).findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt());
        slow.tryEmitValue(List.of(1L));
    }

    @Test
    @DisplayName("a failed build is not retried for a minute")
    void failureWaits() throws InterruptedException {
        when(store.findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("database busy")));

        // Many lookups while the clock stands still: only the first builds.
        for (int i = 0; i < 20; i++) {
            lookup();
            Thread.sleep(10);
        }
        verify(store, times(1)).findDiversifiedFeedIds(any(), any(), any(), any(), anyInt(), anyInt());

        clock.advance(DiverseFeedIndex.RETRY_AFTER_FAILURE.plusSeconds(1));
        eventually(() -> {
            lookup();
            return mockingDetails(store).getInvocations().size() >= 2;
        });
    }

    @Test
    @DisplayName("filter combinations are told apart, and only a few are kept")
    void keysAreSeparateAndBounded() {
        builds(List.of(1L));
        // Each lookup that finds nothing starts a build; wait for each to land.
        for (int maxAge = 1; maxAge <= DiverseFeedIndex.MAX_KEYS; maxAge++) {
            final int days = maxAge;
            eventually(() -> index.idsOrNull("all", null, days, ExplicitJobFilters.none()) != null);
        }

        assertThat(index.idsOrNull("all", null, 99, ExplicitJobFilters.none()))
                .as("a ninth combination is not kept: it gets the ordinary feed")
                .isNull();
        assertThat(index.idsOrNull("all", null, 1, ExplicitJobFilters.none()))
                .as("combinations already kept keep working")
                .isNotNull();
        assertThat(DiverseFeedIndex.keyFor("all", null, 60, ExplicitJobFilters.none()))
                .isNotEqualTo(DiverseFeedIndex.keyFor("all", null, 60, new ExplicitJobFilters("REMOTE", false, null, false)));
    }
}
