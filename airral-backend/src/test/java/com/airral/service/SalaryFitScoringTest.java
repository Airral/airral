package com.airral.service;

import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Salary matching against a candidate's stated expectations.
 *
 * <p>This scorer re-parses the salary label as free text, and its output is
 * user-visible: the score drives the "Salary in range" and "Salary below
 * expectations" chips. Wrong numbers here are worse than missing ones, because
 * the candidate acts on them.
 *
 * <p>Each case below is a real posting phrasing that produced a fabricated
 * figure. They matter more now than they did: until the sync started carrying
 * employer pay, most labels were the placeholder and this code had nothing to
 * misread.
 */
class SalaryFitScoringTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class),
            mock(GreenhouseJobBoardClient.class),
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

    private int score(String label, long min, long max) {
        Integer result = ReflectionTestUtils.invokeMethod(
                service, "scoreSalaryRangeFit", label, BigDecimal.valueOf(min), BigDecimal.valueOf(max));
        return result == null ? 0 : result;
    }

    /** What the scorer returns when there is no figure to read. */
    private static final int NO_FIGURE = 3;
    private static final int OVERLAP = 7;

    @Test
    @DisplayName("a 401k mention is not a $401,000 salary")
    void retirementPlanIsNotPay() {
        // The expectations here bracket $401,000 deliberately: if "401k" is read
        // as pay it scores a full overlap and tells the candidate the money fits.
        assertThat(score("Competitive salary plus 401k match", 350_000, 450_000))
                .as("401k must not be read as a figure")
                .isEqualTo(NO_FIGURE);

        assertThat(score("Benefits include 401(k) matching and equity", 350_000, 450_000))
                .isEqualTo(NO_FIGURE);
        assertThat(score("403b retirement plan available", 350_000, 450_000))
                .isEqualTo(NO_FIGURE);
    }

    @Test
    @DisplayName("a bonus percentage is not a salary, and does not become the top of the range")
    void percentageIsNotPay() {
        // Before: "20" became $20,000 and, as the second figure found, the range
        // ceiling -- so an $85,000 posting was scored against a $20,000 maximum
        // and reported as below a candidate who wanted $80,000-$100,000.
        assertThat(score("Up to $85,000 plus 20% bonus", 80_000, 100_000))
                .as("$85,000 sits inside the candidate's range")
                .isEqualTo(OVERLAP);
    }

    @Test
    @DisplayName("a bare integer is not pay")
    void bareNumberIsNotPay() {
        assertThat(score("Requires 5 years of experience", 350_000, 450_000))
                .isEqualTo(NO_FIGURE);
        assertThat(score("Team of 250 engineers", 200_000, 300_000))
                .isEqualTo(NO_FIGURE);
    }

    @Test
    @DisplayName("a real range still reads correctly")
    void realRangesStillWork() {
        assertThat(score("$180,000 - $230,000", 150_000, 250_000)).isEqualTo(OVERLAP);
        assertThat(score("USD $180k-$230k", 150_000, 250_000)).isEqualTo(OVERLAP);
        assertThat(score("$180k - $230k", 200_000, 220_000)).isEqualTo(OVERLAP);
    }

    @Test
    @DisplayName("a job paying well below expectations is still penalised")
    void genuineShortfallIsStillPenalised() {
        // The repair must not turn every mismatch into a neutral score.
        assertThat(score("$60,000 - $70,000", 150_000, 200_000))
                .as("an $80k gap is a real mismatch")
                .isNegative();
    }

    @Test
    @DisplayName("a high-then-low pair is ordered before comparison")
    void rangeIsOrdered() {
        // Reversed in the text; the candidate's range sits inside it either way.
        assertThat(score("$230,000 down from $180,000", 190_000, 210_000))
                .isEqualTo(OVERLAP);
    }
}
