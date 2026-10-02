package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A signed-in search finds what was typed.
 *
 * <p>It did not. Every signed-in search also retrieved the candidate's target
 * roles and skills and merged them in, then hid whatever fell outside the saved
 * roles and location. On the local corpus, a "Data analyst" profile searching
 * "nurse", "sales associate" or "marketing" got 0 of 10 top results naming what
 * they typed; "bio tech" for a software engineer came back as their usual feed.
 * And each typed search cost five sequential queries, 4-7 seconds in production.
 */
class TypedSearchRespectsQueryTest {

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
        when(store.findRecommendedJobs(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Flux.empty());
        when(store.findJobsBySkills(any(), anyInt(), anyInt(), any())).thenReturn(Flux.empty());
    }

    private Object dataAnalyst() {
        CandidateProfile profile = CandidateProfile.builder()
                .skills(Json.of("[\"SQL\",\"Tableau\"]"))
                .matchPreferences(Json.of("{\"targetRoles\":[\"Data analyst\"]}"))
                .build();
        return ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
    }

    private List<String> retrievedQueriesFor(String query) {
        Mono<List<CandidateJobSummaryResponse>> candidates = ReflectionTestUtils.invokeMethod(
                service, "loadPersonalizedRankingCandidates",
                "all", null, 500, 60, query, null, ExplicitJobFilters.none(), dataAnalyst());
        candidates.block(Duration.ofSeconds(5));
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).findRecommendedJobs(
                any(), any(), any(), any(), any(), queries.capture(), any(), any());
        return queries.getAllValues();
    }

    private CandidateJobSummaryResponse job(String title, String company) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:t:" + title.hashCode() + company.hashCode())
                .sourceType("GREENHOUSE").sourceName("Greenhouse").sourceBoardToken("t")
                .externalJobId(title).title(title).companyName(company)
                .location("Remote").workMode("REMOTE").employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now()).jobQualityScore(80).tags(List.of())
                .build();
    }

    private Object rank(String query, boolean ignorePreferences, CandidateJobSummaryResponse... jobs) {
        return ReflectionTestUtils.invokeMethod(
                service, "rankPersonalizedJobs", List.of(jobs), dataAnalyst(), query, ignorePreferences);
    }

    @SuppressWarnings("unchecked")
    private static List<CandidateJobSummaryResponse> jobsOf(Object ranked) {
        return (List<CandidateJobSummaryResponse>) ReflectionTestUtils.invokeMethod(ranked, "jobs");
    }

    private static int hiddenOf(Object ranked) {
        return (Integer) ReflectionTestUtils.invokeMethod(ranked, "hidden");
    }

    @Test
    @DisplayName("a typed search retrieves what was typed, not the target roles or skills")
    void typedSearchRetrievesOnlyTheQuery() {
        assertThat(retrievedQueriesFor("bio tech"))
                .as("the phrase, and the joined form postings actually use")
                .containsExactlyInAnyOrder("bio tech", "biotech");
        verify(store, never()).findJobsBySkills(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("the feed is still built from the target roles and skills")
    void feedStillUsesTheProfile() {
        // The repair must not empty the feed: with nothing typed, the profile is
        // the only thing to search by.
        // "Data analyst" retrieves as "data": retrievalQueryFromRole drops the
        // generic noun. The point is only that a role-derived search runs.
        assertThat(retrievedQueriesFor(""))
                .as("a target-role retrieval runs alongside the plain feed")
                .anyMatch(query -> query != null && !query.isBlank());
        verify(store).findJobsBySkills(any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("what was typed ranks first: title, then employer, then a mention")
    void typedWordsRankFirst() {
        CandidateJobSummaryResponse analyst = job("Senior Data Analyst", "Brightline");
        CandidateJobSummaryResponse byEmployer = job("Scheduling Coordinator", "Nurse Staffing Partners");
        CandidateJobSummaryResponse nurse = job("Registered Nurse", "St. Brendan Hospital");

        assertThat(jobsOf(rank("nurse", true, analyst, byEmployer, nurse)))
                .extracting(CandidateJobSummaryResponse::getTitle)
                .as("the data analyst's own role family no longer outranks what they typed")
                .containsExactly("Registered Nurse", "Scheduling Coordinator", "Senior Data Analyst");
    }

    @Test
    @DisplayName("saved roles that hide matches are counted, and skipped when asked")
    void preferencesAreCountedAndCanBeIgnored() {
        CandidateJobSummaryResponse analyst = job("Senior Data Analyst", "Brightline");
        CandidateJobSummaryResponse nurse = job("Registered Nurse", "St. Brendan Hospital");

        Object narrowed = rank("nurse", false, analyst, nurse);
        assertThat(jobsOf(narrowed)).extracting(CandidateJobSummaryResponse::getTitle)
                .doesNotContain("Registered Nurse");
        assertThat(hiddenOf(narrowed)).as("the portal asks from this count").isEqualTo(1);

        Object everything = rank("nurse", true, analyst, nurse);
        assertThat(jobsOf(everything)).extracting(CandidateJobSummaryResponse::getTitle)
                .contains("Registered Nurse");
        assertThat(hiddenOf(everything)).as("still reported, so the portal can say what it skipped").isEqualTo(1);
    }

    @Test
    @DisplayName("two-word queries are also searched joined; others are not")
    void joinedVariant() {
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "joinedQueryVariant", "bio tech")).isEqualTo("biotech");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "joinedQueryVariant", "Bio-Tech")).isEqualTo("biotech");
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "joinedQueryVariant", "nurse")).isNull();
        assertThat((String) ReflectionTestUtils.invokeMethod(service, "joinedQueryVariant", "senior data analyst")).isNull();
    }
}
