package com.airral.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.service.CandidateJobSearchService;
import com.airral.service.ExplicitJobFilters;
import com.airral.service.ExternalJobPostingStore;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class InProcessJobCatalogTest {

    private final CandidateJobSearchService service = mock(CandidateJobSearchService.class);
    private final ExternalJobPostingStore store = mock(ExternalJobPostingStore.class);
    private final InProcessJobCatalog catalog = new InProcessJobCatalog(service, store, 60);

    private static CandidateJobSummaryResponse job(String id, String location, String workMode) {
        return CandidateJobSummaryResponse.builder()
                .jobId("greenhouse:acme:" + id)
                .title("Data Analyst " + id)
                .location(location)
                .workMode(workMode)
                .build();
    }

    private void servicePageAnswers(List<CandidateJobSummaryResponse> jobs) {
        when(service.getRecommendedJobsPage(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(CandidateJobPageResponse.builder().jobs(jobs).build()));
    }

    // ── no location: the website's request ──

    @Test
    @DisplayName("work mode and salary go to the page query as filters, signed out, as the website sends them")
    void filtersReachThePageQuery() {
        List<CandidateJobSummaryResponse> remote = List.of(
                job("1", "Remote - US", "REMOTE"), job("2", "Remote", "REMOTE"));
        servicePageAnswers(remote);

        List<CandidateJobSummaryResponse> found =
                catalog.search("data analyst", null, "REMOTE", null, true, 25).block();

        // The page asked for is the page returned: the database applied the
        // filters, so nothing is over-fetched or narrowed here. The old path
        // took the newest 100 matches in any work mode and filtered those.
        verify(service).getRecommendedJobsPage(
                "all", null, 25, 0, 60, "data analyst", null,
                "REMOTE", Boolean.TRUE, null, null, null);
        verify(service, never()).getRecommendedJobs(any(), any(), any(), any(), any(), any());
        verify(service, never()).getRecommendedJobs(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(store);
        assertEquals(remote, found);
    }

    @Test
    @DisplayName("filters not asked for are left off, rather than sent as false or blank")
    void unsetFiltersAreNotSent() {
        servicePageAnswers(List.of());

        catalog.search("  nurse  ", "  ", " ", "", false, 10).block();

        // A blank location means anywhere, so this is the plain page request.
        verify(service).getRecommendedJobsPage(
                "all", null, 10, 0, 60, "nurse", null,
                null, null, null, null, null);
        verifyNoInteractions(store);
    }

    // ── location: one more predicate ──

    @Test
    @DisplayName("a location is narrowed in SQL alongside the other filters, not over the newest rows")
    void locationIsNarrowedInTheDatabase() {
        List<CandidateJobSummaryResponse> texas = List.of(
                job("tx1", "Austin, TX", "ONSITE"), job("tx2", "Dallas, TX", "ONSITE"));
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any(String.class)))
                .thenReturn(Flux.fromIterable(texas));

        List<CandidateJobSummaryResponse> found =
                catalog.search("nurse", " TX ", "ONSITE", "Baylor", true, 25).block();

        // Exactly the limit: no over-fetch, because nothing is filtered after.
        verify(store).findRecommendedJobs(
                "all", null, 25, 0, 60, "nurse", "Baylor",
                new ExplicitJobFilters("ONSITE", Boolean.TRUE, null, null), "TX");
        verifyNoInteractions(service);
        assertEquals(texas, found);
    }

    @Test
    @DisplayName("a location search covers the same freshness window as the page path")
    void locationSearchUsesTheConfiguredWindow() {
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any(), any(String.class)))
                .thenReturn(Flux.empty());

        new InProcessJobCatalog(service, store, 30).search("nurse", "TX", null, null, false, 10).block();
        new InProcessJobCatalog(service, store, 0).search("nurse", "TX", null, null, false, 10).block();
        new InProcessJobCatalog(service, store, 90).search("nurse", "TX", null, null, false, 10).block();

        ExplicitJobFilters none = new ExplicitJobFilters(null, null, null, null);
        // Narrower config is honoured; off and wider fall back to the 60-day ceiling,
        // as CandidateJobSearchService resolves them.
        verify(store).findRecommendedJobs("all", null, 10, 0, 30, "nurse", null, none, "TX");
        verify(store, org.mockito.Mockito.times(2))
                .findRecommendedJobs("all", null, 10, 0, 60, "nurse", null, none, "TX");
    }
}
