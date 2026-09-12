package com.airral.service;

import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the quality chips on a job card are allowed to say.
 *
 * <p>The list is capped at five, so a reason that is true of every posting is not
 * free -- it takes a slot from one that separates this card from the next one.
 * "Fresh source date" was exactly that: it fired whenever the source stated an
 * updated timestamp, which every board we read does, so it appeared on
 * effectively every card and told a candidate nothing. Removed here.
 *
 * <p>The signal that does separate postings is the count of postings an employer
 * has under one title, and it was already being computed:
 * ExternalJobPostingStore.recomputeJobQuality groups active postings by employer
 * and lowercased title and takes 20 points off a group of six or more. It now
 * writes that count back as a reason, worded as the count and nothing else -- the
 * grouping spans every location, so it cannot tell a reposted requisition from a
 * retailer hiring the same role in six stores, and a chip saying "copies" or
 * "reposts" would pick one of those without evidence. That half is SQL over the
 * whole catalogue and cannot be exercised without a database, so what is pinned
 * here is the Java half: every reason this builder emits is conditional on data
 * that varies between postings.
 *
 * <p>Deliberately nothing about listing age. first_seen_at records when we first
 * saw a posting rather than when the employer published it, and the column only
 * exists from migration V7, so the corpus holds a few weeks of it -- the scoring
 * penalty for an age over 120 days has never fired for a single row. A chip
 * reading "Listed 147 days" would be the same overstatement this change exists to
 * remove.
 */
class QualityReasonSignalTest {

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

    private static final String LONG_BODY = "x".repeat(400);

    private List<String> reasons(
            String salaryLabel,
            String location,
            String applyUrl,
            String jobUrl,
            String department,
            String descriptionText) {
        return ReflectionTestUtils.invokeMethod(
                service, "buildQualityReasons", salaryLabel, location, applyUrl, jobUrl, department, descriptionText);
    }

    @Test
    @DisplayName("a posting the source dated like every other posting earns no chip for it")
    void freshSourceDateIsNoLongerAReason() {
        List<String> everythingPresent = reasons(
                "$160k - $220k", "Boston, MA", "https://boards.example.com/apply", null, "Engineering", LONG_BODY);

        assertThat(everythingPresent).doesNotContain("Fresh source date");
        assertThat(everythingPresent).noneMatch(reason -> reason.toLowerCase().contains("fresh"));
    }

    @Test
    @DisplayName("every reason is earned by data that differs between postings")
    void reasonsAreConditionalNotConstant() {
        // A posting that has nothing going for it says only the one thing that is
        // true of it. If a constant ever creeps back in, this is where it shows up.
        assertThat(reasons("Salary not listed", null, null, null, null, null))
                .containsExactly("Needs salary benchmark");

        assertThat(reasons("$160k - $220k", "Boston, MA", "https://boards.example.com/apply", null, "Engineering", LONG_BODY))
                .containsExactly(
                        "Employer salary listed",
                        "Location clear",
                        "Direct apply link",
                        "Team listed",
                        "Full description cached");
    }

    @Test
    @DisplayName("a location placeholder is not a clear location")
    void placeholdersDoNotEarnReasons() {
        assertThat(reasons("Salary not listed", "Location not listed", null, null, "   ", null))
                .containsExactly("Needs salary benchmark");
    }

    @Test
    @DisplayName("no chip claims anything about how long the posting has been listed")
    void nothingClaimsListingAge() {
        List<String> everythingPresent = reasons(
                "$160k - $220k", "Boston, MA", "https://boards.example.com/apply", null, "Engineering", LONG_BODY);

        assertThat(everythingPresent).noneMatch(reason -> {
            String lower = reason.toLowerCase();
            return lower.contains("listed for") || lower.contains("days") || lower.contains("posted ");
        });
    }
}
