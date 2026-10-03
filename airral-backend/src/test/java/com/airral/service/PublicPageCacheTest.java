package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A signed-out page is kept briefly, keyed by everything that shapes it. */
class PublicPageCacheTest {

    private ExternalJobPostingStore store;
    private CandidateJobSearchService service;

    @BeforeEach
    void setUp() {
        store = mock(ExternalJobPostingStore.class);
        service = new CandidateJobSearchService(
                store, mock(GreenhouseJobBoardClient.class),
                mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
                mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
                mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
                mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
                mock(UserRepository.class), new ObjectMapper(),
                "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 2000, 0, 1);
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.just(CandidateJobSummaryResponse.builder().jobId("j1").title("Job").build()));
    }

    private CandidateJobPageResponse page(String location, int offset) {
        return service.getRecommendedJobsPage("all", null, 50, offset, 60, null, null, null, null, null, null,
                null, false, location).block(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("the same place asked twice reads the database once")
    void repeatIsCached() {
        page("Denver, CO", 0);
        page("denver, co ", 0);
        verify(store, times(1)).findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a different place or page is a different entry")
    void keyedByPlaceAndPage() {
        page("Denver, CO", 0);
        page("Austin, TX", 0);
        page("Denver, CO", 50);
        verify(store, times(3)).findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failed load is not kept")
    void failuresAreNotCached() {
        AtomicInteger calls = new AtomicInteger();
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> calls.incrementAndGet() == 1
                        ? Flux.error(new IllegalStateException("db busy"))
                        : Flux.just(CandidateJobSummaryResponse.builder().jobId("j1").title("Job").build()));

        Mono<CandidateJobPageResponse> first = service.getRecommendedJobsPage(
                "all", null, 50, 0, 60, null, null, null, null, null, null, null, false, "Denver, CO");
        try {
            first.block(Duration.ofSeconds(5));
        } catch (RuntimeException expected) {
            // the first load fails
        }
        assertThat(page("Denver, CO", 0).getJobs()).hasSize(1);
    }

    @Test
    @DisplayName("warming a place stores the page the portal asks for first")
    void warmingFillsTheCache() {
        service.warmPublicPlacePage("Denver, CO", 50).block(Duration.ofSeconds(5));
        page("Denver, CO", 0);
        verify(store, times(1)).findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("filters are part of the key")
    void filtersMatter() {
        assertThat(CandidateJobSearchService.publicPageKey("all", null, 60, null, null, "Denver, CO",
                new ExplicitJobFilters("REMOTE", null, null, null), 50, 0))
                .isNotEqualTo(CandidateJobSearchService.publicPageKey("all", null, 60, null, null, "Denver, CO",
                        ExplicitJobFilters.none(), 50, 0));
        assertThat(List.of(1)).isNotEmpty();
    }
}
