package com.airral.service;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobPageResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.exception.BadRequestException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The promises the search and detail endpoints make to a caller.
 *
 * <p>Three defects observed in production, each of which a unit test can pin
 * without a database or a job board:
 *
 * <ul>
 *   <li>hasMore lied at the maximum page size, so a bulk consumer following it
 *       saw 500 of ~15,330 postings and stopped.</li>
 *   <li>The sponsorship classifier read ordinary offer boilerplate as an
 *       authorization demand and docked the posting 16 points for it.</li>
 *   <li>The first person to open any job got a 500 carrying a Reactor timeout
 *       message whenever the board was slow.</li>
 * </ul>
 */
class SearchContractTest {

    private ExternalJobPostingStore store;
    private GreenhouseJobBoardClient greenhouseClient;
    private CandidateJobSearchService service;

    @BeforeEach
    void setUp() {
        store = mock(ExternalJobPostingStore.class);
        greenhouseClient = mock(GreenhouseJobBoardClient.class);
        service = new CandidateJobSearchService(
                store,
                greenhouseClient,
                mock(LeverJobBoardClient.class),
                mock(AshbyJobBoardClient.class),
                mock(SmartRecruitersJobBoardClient.class),
                mock(WorkableJobBoardClient.class),
                mock(WorkdayJobBoardClient.class),
                mock(BambooHrJobBoardClient.class),
                mock(CareerPageJobBoardClient.class),
                mock(CandidateProfileRepository.class),
                mock(UserRepository.class),
                new ObjectMapper(),
                "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 0, 1);
    }

    @Nested
    @DisplayName("paging")
    class Paging {

        /**
         * Answers every query with a full page, the way a catalogue far larger
         * than any single request does. Whether hasMore is right then depends
         * entirely on the service asking for one row more than it intends to
         * return.
         */
        private void storeAlwaysHasMore() {
            when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> Flux.fromIterable(postings(invocation.getArgument(2, Integer.class))));
        }

        private List<CandidateJobSummaryResponse> postings(int count) {
            List<CandidateJobSummaryResponse> jobs = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                jobs.add(CandidateJobSummaryResponse.builder()
                        .jobId("greenhouse:airbnb:" + index)
                        .sourceType("GREENHOUSE")
                        .sourceBoardToken("airbnb")
                        .externalJobId(String.valueOf(index))
                        .title("Software Engineer " + index)
                        .companyName("Airbnb")
                        .applyUrl("https://boards.example/airbnb/" + index)
                        .build());
            }
            return jobs;
        }

        private CandidateJobPageResponse page(Integer limit, Integer offset) {
            return service.getRecommendedJobsPage("all", null, limit, offset, 45, null, null).block();
        }

        @Test
        @DisplayName("the probe row survives the maximum page size")
        void hasMoreIsCorrectAtTheLargestAllowedLimit() {
            storeAlwaysHasMore();
            ArgumentCaptor<Integer> queryLimit = ArgumentCaptor.forClass(Integer.class);

            CandidateJobPageResponse response = page(500, 0);

            verify(store).findRecommendedJobs(
                    any(), any(), queryLimit.capture(), any(), any(), any(), any(), any());
            // 500 was the bug: the probe row was clamped to the same cap as the
            // page, so the page and the probe were the same rows.
            assertThat(queryLimit.getValue()).isEqualTo(501);
            assertThat(response.getJobs()).hasSize(500);
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getNextOffset()).isEqualTo(500);
        }

        @Test
        @DisplayName("a limit above the cap is still paged, not silently ended")
        void hasMoreIsCorrectWhenTheCallerAsksForMoreThanTheCap() {
            storeAlwaysHasMore();

            CandidateJobPageResponse response = page(5000, 1000);

            assertThat(response.getLimit()).isEqualTo(500);
            assertThat(response.getJobs()).hasSize(500);
            assertThat(response.isHasMore()).isTrue();
            assertThat(response.getNextOffset()).isEqualTo(1500);
        }

        @Test
        @DisplayName("the portal's own page size keeps working")
        void hasMoreIsCorrectAtTheDefaultLimit() {
            storeAlwaysHasMore();
            ArgumentCaptor<Integer> queryLimit = ArgumentCaptor.forClass(Integer.class);

            CandidateJobPageResponse response = page(null, 0);

            verify(store).findRecommendedJobs(
                    any(), any(), queryLimit.capture(), any(), any(), any(), any(), any());
            assertThat(queryLimit.getValue()).isEqualTo(51);
            assertThat(response.getJobs()).hasSize(50);
            assertThat(response.isHasMore()).isTrue();
        }

        @Test
        @DisplayName("the last page does not claim a next one")
        void hasMoreIsFalseOnAShortPage() {
            when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(Flux.fromIterable(postings(137)));

            CandidateJobPageResponse response = page(500, 0);

            assertThat(response.getJobs()).hasSize(137);
            assertThat(response.isHasMore()).isFalse();
            assertThat(response.getNextOffset()).isNull();
        }
    }

    @Nested
    @DisplayName("sponsorship classification")
    class Sponsorship {

        private String classify(String descriptionText) {
            CandidateJobSummaryResponse job = CandidateJobSummaryResponse.builder()
                    .jobId("greenhouse:samsara:1")
                    .sourceType("GREENHOUSE")
                    .title("Software Engineer, Platform")
                    .companyName("Samsara")
                    .location("San Francisco, CA")
                    .build();
            ReflectionTestUtils.invokeMethod(service, "applyVisaSignals", job, descriptionText);
            return job.getSponsorshipLanguage();
        }

        @Test
        @DisplayName("offer boilerplate about securing the right to work is neutral")
        void contingencyBoilerplateIsNotAnAuthorizationDemand() {
            // Samsara's standard closing paragraph. Securing the right to work is
            // what sponsorship achieves, so reading this as an authorization
            // demand inverted the signal for the candidates it matters to.
            assertThat(classify("All offers of employment are contingent upon the candidate's "
                    + "ability to secure the right to work in the United States."))
                    .isEqualTo("UNKNOWN");

            assertThat(classify("Any offer is conditional on your ability to obtain the right to "
                    + "work in the country where the role is based."))
                    .isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("labour-law and remote-work uses of the phrase are neutral")
        void unrelatedUsesOfTheWordsAreNotAuthorizationDemands() {
            assertThat(classify("Our Austin office is in a right-to-work state."))
                    .isEqualTo("UNKNOWN");
            assertThat(classify("You have the right to work from anywhere in the country."))
                    .isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("a real authorization requirement still classifies")
        void genuineAuthorizationRequirementsStillMatch() {
            assertThat(classify("Applicants must have the right to work in the United States."))
                    .isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(classify("This role requires the legal right to work in the US."))
                    .isEqualTo("AUTHORIZATION_REQUIRED");
            // "a valid right to work in" is how UK and Australian postings almost
            // always word it, and the adjective slot originally listed everything
            // but that one.
            assertThat(classify("Applicants must hold a valid right to work in Australia."))
                    .isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(classify("On your first day you will be asked for proof of your right to work."))
                    .isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(classify("You must be authorized to work in the United States."))
                    .isEqualTo("AUTHORIZATION_REQUIRED");
        }

        @Test
        @DisplayName("a refusal is still a refusal")
        void refusalsStillClassify() {
            assertThat(classify("We do not provide visa sponsorship for this position."))
                    .isEqualTo("NO_SPONSORSHIP");
            assertThat(classify("No visa sponsorship is available for this role."))
                    .isEqualTo("NO_SPONSORSHIP");
        }

        @Test
        @DisplayName("an offer to sponsor is still an offer")
        void offersStillClassify() {
            assertThat(classify("Visa sponsorship is available for this role."))
                    .isEqualTo("SPONSORS");
            // The qualified offer the refusal pattern was deliberately built to
            // leave alone; reverting to a refusal here inverts the answer for an
            // employer who genuinely sponsors.
            assertThat(classify("We do sponsor visas! Visa sponsorship is available, though we "
                    + "aren't able to sponsor for every role."))
                    .isEqualTo("SPONSORS");
        }

        @Test
        @DisplayName("a bare negation does not read deal sponsors as a refusal")
        void previouslyRevertedFalsePositiveStaysOut() {
            // The over-correction that had to be reverted: "no" as a standalone
            // term matched "no-go" and "sponsors" sat inside the window.
            assertThat(classify("Provide go/no-go input to deal sponsors and the investment "
                    + "committee."))
                    .isEqualTo("UNKNOWN");
        }
    }

    @Nested
    @DisplayName("job detail on a cold cache")
    class ColdCacheDetail {

        private CandidateJobDetailResponse storedRow() {
            return CandidateJobDetailResponse.builder()
                    .jobId("greenhouse:airbnb:42")
                    .sourceType("GREENHOUSE")
                    .sourceBoardToken("airbnb")
                    .externalJobId("42")
                    .title("Staff Software Engineer")
                    .companyName("Airbnb")
                    .location("San Francisco, CA")
                    .applyUrl("https://boards.example/airbnb/42")
                    .build();
        }

        private void coldCacheForAnActivePosting() {
            when(store.findCachedJobDetail(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
            when(store.existsActiveJob(anyString(), anyString(), anyString())).thenReturn(Mono.just(true));
        }

        @Test
        @DisplayName("an upstream failure serves the stored posting instead of a 500")
        void upstreamFailureFallsBackToTheStoredPosting() {
            coldCacheForAnActivePosting();
            when(store.findStoredJobDetail("GREENHOUSE", "airbnb", "42")).thenReturn(Mono.just(storedRow()));
            when(greenhouseClient.retrieveJob(anyString(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("Connection prematurely closed")));

            StepVerifier.create(service.getExternalJobDetail("greenhouse", "airbnb", "42"))
                    .assertNext(detail -> {
                        assertThat(detail.getTitle()).isEqualTo("Staff Software Engineer");
                        assertThat(detail.getApplyUrl()).isEqualTo("https://boards.example/airbnb/42");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("a slow board is a degraded page, not a Reactor timeout message")
        void timeoutFallsBackToTheStoredPosting() {
            coldCacheForAnActivePosting();
            when(store.findStoredJobDetail("GREENHOUSE", "airbnb", "42")).thenReturn(Mono.just(storedRow()));
            // A board that never answers. One second rather than the configured
            // eight, so the test costs a second rather than eight.
            ReflectionTestUtils.setField(service, "sourceTimeoutSeconds", 1);
            when(greenhouseClient.retrieveJob(anyString(), any())).thenReturn(Mono.never());

            StepVerifier.create(service.getExternalJobDetail("greenhouse", "airbnb", "42"))
                    .assertNext(detail -> assertThat(detail.getTitle()).isEqualTo("Staff Software Engineer"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("with nothing stored the caller gets a 503, not an internal message")
        void nothingKnownIsAServiceUnavailable() {
            coldCacheForAnActivePosting();
            when(store.findStoredJobDetail("GREENHOUSE", "airbnb", "42")).thenReturn(Mono.empty());
            when(greenhouseClient.retrieveJob(anyString(), any()))
                    .thenReturn(Mono.error(new IllegalStateException(
                            "Did not observe any item or terminal signal within 8000ms "
                                    + "in 'source(MonoDefer)' (and no fallback has been configured)")));

            StepVerifier.create(service.getExternalJobDetail("greenhouse", "airbnb", "42"))
                    .consumeErrorWith(error -> {
                        assertThat(error).isInstanceOf(ResponseStatusException.class);
                        ResponseStatusException failure = (ResponseStatusException) error;
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(failure.getReason())
                                .doesNotContain("fallback")
                                .doesNotContain("Did not observe");
                    })
                    .verify(Duration.ofSeconds(5));
        }

        @Test
        @DisplayName("a deliberate 4xx is not swallowed by the fallback")
        void clientErrorsAreNotDegraded() {
            coldCacheForAnActivePosting();

            StepVerifier.create(service.getExternalJobDetail("greenhouse", "airbnb", "not-a-number"))
                    .expectError(BadRequestException.class)
                    .verify(Duration.ofSeconds(5));
        }
    }
}
