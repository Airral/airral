package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A match percentage exists only when there is something to match against.
 *
 * <p>There used to be one on every posting, for everybody, including people who
 * were not signed in. {@code inferMatchScore} returned 74 if the title
 * contained "frontend", "front-end" or "ui", 70 for "software", "engineer" or
 * "product", and 64 for anything else -- reading no profile, no skills, no
 * stated roles, no user at all. The 64 is the "64% profile match on a job where
 * I did not push my resume" a reviewer reported.
 *
 * <p>The buckets were not even the classification they appeared to be, because
 * "ui" was matched as a substring: "Build Engineer", "Building Maintenance",
 * "Recruiter", "Equity Analyst" and "Guide Services Lead" all scored 74 on the
 * letters u-i, while a Registered Nurse scored 64.
 *
 * <p>What is guarded here is the property, not the deleted function: a score is
 * present exactly when the candidate has told us something, and absent
 * otherwise. Absent has to stay absent rather than become a zero or a default,
 * because the portal decides whether to show the figure from whether it is
 * there.
 */
class MatchScoreOnlyWhenEarnedTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class), mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
            mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
            mock(UserRepository.class), new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 0, 1);

    private List<CandidateJobSummaryResponse> rank(CandidateProfile profile, CandidateJobSummaryResponse... jobs) {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        List<CandidateJobSummaryResponse> ranked =
                ReflectionTestUtils.invokeMethod(service, "rankPersonalizedJobs", List.of(jobs), context);
        return ranked == null ? List.of() : ranked;
    }

    private CandidateJobSummaryResponse job(String title) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:t:" + title.hashCode())
                .sourceType("GREENHOUSE").sourceName("Greenhouse").sourceBoardToken("t")
                .externalJobId("1").title(title).companyName("Co")
                .location("Columbus, OH").employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now()).jobQualityScore(80).tags(List.of())
                .build();
    }

    /**
     * The titles that used to score 74 on the letters u-i, and one that scored
     * 64. None of them may carry a figure for a candidate who said nothing.
     */
    @Test
    @DisplayName("a candidate who has said nothing gets no percentage")
    void nothingStatedMeansNoScore() {
        CandidateProfile saidNothing = CandidateProfile.builder()
                .matchPreferences(Json.of("{}"))
                .build();

        for (String title : List.of(
                "Build Engineer", "Building Maintenance", "Recruiter",
                "Equity Analyst", "Guide Services Lead", "Registered Nurse", "Cashier")) {
            assertThat(rank(saidNothing, job(title)).get(0).getMatchScore())
                    .as("\"" + title + "\" has nothing to be matched against")
                    .isNull();
        }
    }

    @Test
    @DisplayName("a stated preference earns a score, and a reason that explains it")
    void statingSomethingEarnsAScore() {
        CandidateProfile pickedWarehouse = CandidateProfile.builder()
                .matchPreferences(Json.of("{\"targetRoles\":[\"Warehouse\"]}"))
                .build();
        CandidateJobSummaryResponse warehouse = rank(pickedWarehouse, job("Order Picker")).get(0);

        assertThat(warehouse.getMatchScore())
                .as("they told us what they want, so the figure describes something")
                .isNotNull();
        assertThat(warehouse.getMatchReasons())
                .as("a number with no stated reason is the thing this replaces")
                .isNotEmpty();
    }

    /**
     * Absent must stay absent, not become a default.
     *
     * <p>The portal shows the percentage when the field is present, so a zero
     * or a floor here would put "0% match" or "25% match" back on the card for
     * someone with no profile -- the same claim in a different costume.
     */
    @Test
    @DisplayName("no score is not a zero and not a floor")
    void absentIsNotADefault() {
        CandidateProfile saidNothing = CandidateProfile.builder()
                .matchPreferences(Json.of("{}"))
                .build();
        CandidateJobSummaryResponse scored = rank(saidNothing, job("Warehouse Associate")).get(0);

        assertThat(scored.getMatchScore()).isNull();
        assertThat(scored.getMatchReasons()).isNullOrEmpty();
        assertThat(scored.getJobQualityScore())
                .as("job quality is measured from the posting and survives")
                .isEqualTo(80);
    }
}
