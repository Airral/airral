package com.airral.service;

import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.dto.smartrecruiters.SmartRecruitersPostingResponse;
import com.airral.dto.workday.WorkdayJobSearchResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A paginating connector must be able to reach the limit it was given.
 *
 * <p>Three connectors paginate, and all three had a page cap written as a bare
 * number beside the page size: Workday stopped after 25 pages of 20 and
 * SmartRecruiters after 5 pages of 100. Both products are 500, and 500 was also
 * the sync's limit, so for as long as those two numbers agreed the caps were
 * invisible -- every board came back at or under 500 and nothing looked
 * truncated. Raising the limit to 2000 is what separated them: Lowe's Workday
 * board lists 12,608 postings and returned 499.
 *
 * <p>These tests count the calls the connector makes rather than the rows it
 * returns, because the rows are what the cap silently removes. A connector
 * asked for 2000 at a page size of N must ask its client 2000/N times; if it
 * asks fewer, the missing rows never existed as far as any later assertion is
 * concerned.
 */
class ConnectorPagingReachesLimitTest {

    private static final int LIMIT = 2000;

    private final WorkdayJobBoardClient workdayClient = mock(WorkdayJobBoardClient.class);
    private final SmartRecruitersJobBoardClient smartRecruitersClient = mock(SmartRecruitersJobBoardClient.class);

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class),
            mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class),
            mock(AshbyJobBoardClient.class),
            smartRecruitersClient,
            mock(WorkableJobBoardClient.class),
            workdayClient,
            mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class),
            mock(CandidateProfileRepository.class),
            mock(UserRepository.class),
            new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, LIMIT, 12, 4);

    /** A full Workday page: 20 postings, each with the externalPath the filter requires. */
    private WorkdayJobSearchResponse workdayPage(int offset, int size) {
        List<WorkdayJobSearchResponse.WorkdayJobPosting> postings = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            var p = new WorkdayJobSearchResponse.WorkdayJobPosting();
            p.setTitle("Store Associate " + (offset + i));
            p.setExternalPath("/job/" + (offset + i));
            p.setLocationsText("Mooresville, NC");
            postings.add(p);
        }
        var response = new WorkdayJobSearchResponse();
        response.setTotal(12608);
        response.setJobPostings(postings);
        return response;
    }

    @Test
    @DisplayName("Workday pages all the way to the limit rather than stopping at 500")
    void workdayPagesToTheLimit() {
        when(workdayClient.listJobs(any(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> Mono.just(workdayPage(inv.getArgument(2), inv.getArgument(1))));

        Flux<CandidateJobSummaryResponse> jobs = ReflectionTestUtils.invokeMethod(
                service, "workdaySummaries", "lowes.wd5.myworkdayjobs.com|lowes|LWS_External_CS", LIMIT);

        // 20 is Workday's own page maximum -- it answers 400 to anything larger --
        // so reaching 2000 means a hundred calls, not a bigger page.
        assertThat(jobs.collectList().block()).hasSize(LIMIT);
    }

    @Test
    @DisplayName("Workday asks for a page size Workday will actually accept")
    void workdayNeverAsksForMoreThanTwenty() {
        List<Integer> requested = new ArrayList<>();
        when(workdayClient.listJobs(any(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    requested.add(inv.getArgument(1));
                    return Mono.just(workdayPage(inv.getArgument(2), inv.getArgument(1)));
                });

        Flux<CandidateJobSummaryResponse> jobs = ReflectionTestUtils.invokeMethod(
                service, "workdaySummaries", "lowes.wd5.myworkdayjobs.com|lowes|LWS_External_CS", LIMIT);
        jobs.collectList().block();

        assertThat(requested).isNotEmpty();
        assertThat(requested).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("SmartRecruiters pages past its old five-page stop")
    void smartRecruitersPagesToTheLimit() {
        when(smartRecruitersClient.listJobs(anyString(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    int size = inv.getArgument(1);
                    List<SmartRecruitersPostingResponse.Posting> content = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        var p = new SmartRecruitersPostingResponse.Posting();
                        p.setId("p" + inv.getArgument(2) + "-" + i);
                        p.setName("Consultant " + i);
                        content.add(p);
                    }
                    var response = new SmartRecruitersPostingResponse();
                    response.setTotalFound(3270);
                    response.setContent(content);
                    return Mono.just(response);
                });

        Flux<CandidateJobSummaryResponse> jobs = ReflectionTestUtils.invokeMethod(
                service, "smartRecruitersSummaries", "TurnerTownsend", LIMIT);

        assertThat(jobs.collectList().block()).hasSize(LIMIT);
    }
}
