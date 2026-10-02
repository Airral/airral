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
 * Whether a posting's work mode fits what the candidate asked for.
 *
 * <p>A candidate who picked Remote was shown hybrid jobs in San Carlos with a
 * green tick reading "Work mode fits", and those jobs were ranked as if they
 * matched. The scorer treated hybrid as compatible with a remote preference. A
 * hybrid job means commuting to an office some days, which is exactly what a
 * remote preference rules out, so the tick told the candidate something false
 * about a job they would act on.
 */
class WorkModePreferenceFitTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class), mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
            mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
            mock(UserRepository.class), new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 2000, 0, 1);

    private static final String FITS = "Work mode fits";
    private static final String MAY_NOT_FIT = "Work mode may not fit";

    private CandidateJobSummaryResponse ranked(String preferredWorkMode, String jobWorkMode) {
        CandidateProfile profile = CandidateProfile.builder()
                .preferredWorkMode(preferredWorkMode)
                .matchPreferences(Json.of("{\"targetRoles\":[\"Data analyst\"]}"))
                .build();
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        List<CandidateJobSummaryResponse> ranked = ReflectionTestUtils.invokeMethod(
                service, "rankPersonalizedJobs", List.of(job(jobWorkMode)), context);
        assertThat(ranked).hasSize(1);
        return ranked.get(0);
    }

    private CandidateJobSummaryResponse job(String workMode) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:t:" + workMode)
                .sourceType("GREENHOUSE").sourceName("Greenhouse").sourceBoardToken("t")
                .externalJobId(workMode).title("Senior Data Analyst").companyName("Natera")
                .location("San Carlos, CA").workMode(workMode).employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now()).jobQualityScore(80).tags(List.of())
                .build();
    }

    @Test
    @DisplayName("a hybrid job does not fit a candidate who asked for remote")
    void hybridIsNotRemote() {
        CandidateJobSummaryResponse hybrid = ranked("REMOTE", "HYBRID");

        assertThat(hybrid.getMatchReasons())
                .as("the reported San Carlos case: hybrid means an office some days")
                .doesNotContain(FITS)
                .contains(MAY_NOT_FIT);
    }

    @Test
    @DisplayName("for a remote candidate, a remote job outranks the same job hybrid")
    void remoteOutranksHybridForRemoteCandidate() {
        // The tick is the visible half; the score is what orders the list. Both
        // postings are otherwise identical, so the gap is the work mode alone.
        assertThat(ranked("REMOTE", "REMOTE").getMatchScore())
                .isGreaterThan(ranked("REMOTE", "HYBRID").getMatchScore());
    }

    @Test
    @DisplayName("a matching work mode still fits")
    void matchingModeStillFits() {
        // The repair must not turn every work mode into a mismatch.
        assertThat(ranked("REMOTE", "REMOTE").getMatchReasons()).contains(FITS);
        assertThat(ranked("HYBRID", "HYBRID").getMatchReasons()).contains(FITS);
        assertThat(ranked("ONSITE", "ONSITE").getMatchReasons()).contains(FITS);
    }

    @Test
    @DisplayName("an on-site job does not fit a remote candidate")
    void onsiteIsNotRemote() {
        assertThat(ranked("REMOTE", "ONSITE").getMatchReasons())
                .doesNotContain(FITS)
                .contains(MAY_NOT_FIT);
    }
}
