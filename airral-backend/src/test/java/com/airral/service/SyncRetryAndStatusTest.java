package com.airral.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a run does when a board does not answer, and what colour it ends.
 *
 * <p>Measured over 25 production runs: the failures were a different handful of
 * slow Lever and SmartRecruiters boards each time, and every one turned the run
 * red. A board that fails is now tried once more at the end, and the run is only
 * DEGRADED -- red -- when a failing board has had no success for a whole day.
 */
class SyncRetryAndStatusTest {

    private static final Answer<Object> EMPTY_PUBLISHERS = invocation -> {
        Class<?> type = invocation.getMethod().getReturnType();
        if (type == Mono.class) return Mono.empty();
        if (type == Flux.class) return Flux.empty();
        return null;
    };

    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class, EMPTY_PUBLISHERS);
    private final CandidateJobSearchService search = mock(CandidateJobSearchService.class);
    private ExternalJobSyncService service;

    private final ExternalJobSourceRecord steady = source(1L, "steady");
    private final ExternalJobSourceRecord flaky = source(2L, "flaky");

    private static ExternalJobSourceRecord source(Long id, String token) {
        return new ExternalJobSourceRecord(id, 3L, "Acme " + token, "acme.com", "LEVER", token, "Lever");
    }

    private static Mono<CandidateJobSearchService.SourceFetch> ok() {
        return Mono.just(new CandidateJobSearchService.SourceFetch(List.of(), 0));
    }

    private static <T> Mono<T> timeout() {
        return Mono.error(new TimeoutException(
                "Did not observe any item or terminal signal within 45000ms in 'flatMap'"));
    }

    @BeforeEach
    void setUp() {
        service = new ExternalJobSyncService(store, search,
                60, 15, 500, 50, 6, 500, false, true,
                false, 300, 40, 4, "WORKDAY", "airral-test");
        when(store.findActiveSources()).thenReturn(Flux.just(steady, flaky));
        when(store.markSourceSuccess(anyLong())).thenReturn(Mono.just(1L));
        when(store.markSourceError(anyLong(), any())).thenReturn(Mono.just(1L));
        when(store.recomputeJobQuality()).thenReturn(Mono.just(0L));
        when(store.expireOldJobs(anyInt())).thenReturn(Mono.just(0L));
        when(store.purgeExpiredJobs(anyInt())).thenReturn(Mono.just(0L));
        when(store.completeSyncRun(anyLong(), anyString(), anyInt(), anyInt(), anyInt(), anyLong(), any()))
                .thenReturn(Mono.just(1L));
        when(search.fetchForSync(eq("LEVER"), eq("steady"), anyInt(), anyInt())).thenReturn(ok());
    }

    private ExternalJobSyncResult run() {
        Mono<ExternalJobSyncResult> result = ReflectionTestUtils.invokeMethod(service, "syncActiveSourcesForRun", 99L);
        return result == null ? null : result.block();
    }

    @Test
    @DisplayName("a board that times out and then answers is retried, and the run is green")
    void retryRecoversATimeout() {
        when(search.fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt()))
                .thenReturn(timeout(), ok());

        assertThat(run().status()).isEqualTo("SUCCESS");
        verify(search, times(2)).fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt());
        verify(search, times(1)).fetchForSync(eq("LEVER"), eq("steady"), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a board still failing after the retry, that read fine within a day, is a warning, not red")
    void recentlyHealthyFailureIsPartial() {
        when(search.fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt())).thenReturn(timeout());
        when(store.countSourcesWithoutSuccessSince(eq(List.of(2L)), any())).thenReturn(Mono.just(0L));

        assertThat(run().status()).isEqualTo("PARTIAL_SUCCESS");
    }

    @Test
    @DisplayName("a board with no success for a day still turns the run red")
    void staleFailureIsDegraded() {
        when(search.fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt())).thenReturn(timeout());
        when(store.countSourcesWithoutSuccessSince(eq(List.of(2L)), any())).thenReturn(Mono.just(1L));

        assertThat(run().status()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("a board that is gone (404) is disabled and not retried")
    void goneBoardIsNotRetried() {
        when(search.fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt()))
                .thenReturn(Mono.error(new IllegalStateException("Lever board not found (HTTP 404)")));
        when(store.disableSource(anyLong(), any())).thenReturn(Mono.just(1L));
        when(store.deactivatePostingsForSource(anyLong())).thenReturn(Mono.just(0L));

        assertThat(run().status()).isEqualTo("PARTIAL_SUCCESS");
        verify(search, times(1)).fetchForSync(eq("LEVER"), eq("flaky"), anyInt(), anyInt());
    }
}
