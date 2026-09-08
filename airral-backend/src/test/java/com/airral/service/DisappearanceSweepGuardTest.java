package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guards on retiring a posting because its board stopped listing it.
 *
 * <p>This is the most destructive thing the sync can do, and it turns on an
 * inference that is only sometimes valid: a posting absent from the response is
 * absent from the board. Two situations break that inference, and in both of
 * them acting would remove live jobs from an employer's listing at scale. The
 * sweep itself is one UPDATE and the database covers it; what needs pinning is
 * the decision not to run it.
 *
 * <p>These assert on whether the store is asked at all, because that is the
 * safety property -- a guard that computed the right answer and still issued the
 * UPDATE would be no guard.
 */
class DisappearanceSweepGuardTest {

    private static final int LIMIT_PER_SOURCE = 500;

    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class);

    private final ExternalJobSyncService service = new ExternalJobSyncService(
            store,
            mock(CandidateJobSearchService.class),
            60,
            15,
            LIMIT_PER_SOURCE,
            50,
            6,
            500,
            "airral-test");

    private final ExternalJobSourceRecord source = new ExternalJobSourceRecord(
            7L, 3L, "Acme", "acme.com", "GREENHOUSE", "acme", "Greenhouse");

    private long retire(int jobsSeen) {
        Mono<Long> result = ReflectionTestUtils.invokeMethod(
                service, "retireUnseenPostings", source, jobsSeen,
                OffsetDateTime.now(ZoneOffset.UTC));
        Long retired = result == null ? null : result.block();
        return retired == null ? -1 : retired;
    }

    @Test
    @DisplayName("a complete fetch retires what the board no longer lists")
    void completeFetchSweeps() {
        when(store.deactivateUnseenPostings(anyLong(), any())).thenReturn(Mono.just(4L));

        assertThat(retire(120)).isEqualTo(4L);
        verify(store, times(1)).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("an empty response is never treated as an empty board")
    void emptyResponseDoesNotSweep() {
        // A board with no open roles and a board that answered 200 with nothing
        // useful are indistinguishable from here. Acting on the second would
        // retire an employer's entire listing off one bad response.
        assertThat(retire(0)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("a fetch that hit the page limit does not sweep")
    void truncatedFetchDoesNotSweep() {
        // The dangerous case, because it looks healthy. On a board with more
        // postings than the limit, the ones we never asked for are
        // indistinguishable from the ones taken down -- sweeping would retire real
        // jobs every run and resurrect them on the next, churning the largest
        // employers hardest.
        assertThat(retire(LIMIT_PER_SOURCE)).isZero();
        verify(store, never()).deactivateUnseenPostings(anyLong(), any());
    }

    @Test
    @DisplayName("one posting short of the limit is still a complete fetch")
    void justUnderTheLimitSweeps() {
        // The boundary matters: refusing here would exempt a board sitting exactly
        // one posting below the cap forever.
        when(store.deactivateUnseenPostings(anyLong(), any())).thenReturn(Mono.just(1L));

        assertThat(retire(LIMIT_PER_SOURCE - 1)).isEqualTo(1L);
        verify(store, times(1)).deactivateUnseenPostings(anyLong(), any());
    }
}
