package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * When the public feed is served from the diversified list, and when it is not.
 *
 * <p>The list orders the feed with no employer filling the front; 49 of the first
 * 50 jobs were Target's before it. It is only the feed: a search for "target" must
 * still return Target's jobs. And with no list ready it must fall back, because a
 * missing list is not an error to show a visitor.
 */
class DiversifiedFeedPageTest {

    private ExternalJobPostingStore store;
    private DiverseFeedIndex index;
    private CandidateJobSearchService service;

    @BeforeEach
    void setUp() {
        store = mock(ExternalJobPostingStore.class);
        index = mock(DiverseFeedIndex.class);
        service = new CandidateJobSearchService(
                store, mock(GreenhouseJobBoardClient.class),
                mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
                mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
                mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
                mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
                mock(UserRepository.class), new ObjectMapper(),
                "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 2000, 0, 1);
        ReflectionTestUtils.invokeMethod(service, "setDiverseFeedIndex", index);
        when(store.findJobsByIds(any())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            return Flux.fromIterable(ids).map(id -> job(id));
        });
    }

    private static CandidateJobSummaryResponse job(long id) {
        return CandidateJobSummaryResponse.builder().jobId("j" + id).title("Job " + id).build();
    }

    private static List<Long> ids(int count) {
        List<Long> ids = new ArrayList<>();
        for (long id = 1; id <= count; id++) {
            ids.add(id);
        }
        return ids;
    }

    private CandidateJobPageResponse page(String query, String company, int limit, int offset) {
        Mono<CandidateJobPageResponse> page = ReflectionTestUtils.invokeMethod(
                service, "diversifiedFeedPage", "all", null, 60, query, company, ExplicitJobFilters.none(), limit, offset);
        return page.block(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a page is the requested slice of the list, one extra to learn whether there is a next page")
    void servesASlice() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(new DiverseFeedIndex.Feed(ids(500), true));

        CandidateJobPageResponse page = page(null, null, 50, 100);

        ArgumentCaptor<List<Long>> asked = ArgumentCaptor.forClass(List.class);
        verify(store).findJobsByIds(asked.capture());
        assertThat(asked.getValue()).hasSize(51).startsWith(101L).endsWith(151L);
        assertThat(page.getJobs()).hasSize(50);
        assertThat(page.isHasMore()).isTrue();
        assertThat(page.getOffset()).isEqualTo(100);
    }

    @Test
    @DisplayName("the last page of a complete list says there is no more")
    void lastPage() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(new DiverseFeedIndex.Feed(ids(120), true));

        CandidateJobPageResponse page = page("", "", 50, 100);

        assertThat(page.getJobs()).hasSize(20);
        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("past the end of a complete list is an empty page, not a fall back to the plain feed")
    void pastTheEndOfACompleteList() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(new DiverseFeedIndex.Feed(ids(120), true));

        CandidateJobPageResponse page = page(null, null, 50, 150);

        assertThat(page).isNotNull();
        assertThat(page.getJobs()).isEmpty();
        assertThat(page.isHasMore()).isFalse();
    }

    @Test
    @DisplayName("past the end of a truncated list falls back, since more exists behind it")
    void pastTheEndOfATruncatedList() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(new DiverseFeedIndex.Feed(ids(5000), false));

        assertThat(page(null, null, 50, 5000)).as("null: the caller serves the plain feed").isNull();
    }

    @Test
    @DisplayName("no list ready means the plain feed")
    void noListYet() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(null);

        assertThat(page(null, null, 50, 0)).isNull();
        verify(store, never()).findJobsByIds(any());
    }

    @Test
    @DisplayName("a search is never served from the list: a search for target returns Target's jobs")
    void searchesBypassTheList() {
        when(index.idsOrNull(any(), any(), anyInt(), any())).thenReturn(new DiverseFeedIndex.Feed(ids(500), true));

        assertThat(page("target", null, 50, 0)).isNull();
        assertThat(page(null, "Target", 50, 0)).isNull();
        verify(index, never()).idsOrNull(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("without an index the service behaves as it always did")
    void noIndexAtAll() {
        ReflectionTestUtils.invokeMethod(service, "setDiverseFeedIndex", (Object) null);

        assertThat(page(null, null, 50, 0)).isNull();
    }
}
