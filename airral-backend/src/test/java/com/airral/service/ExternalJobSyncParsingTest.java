package com.airral.service;

import com.airral.dto.greenhouse.GreenhouseJobBoardResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Covers the sync write path -- the mapper whose output is what actually gets
 * persisted.
 *
 * <p>An audit found this path was strictly less informed than the detail path
 * beside it. The summary mapper hardcoded {@code "Salary not listed"} while the
 * detail mapper read the employer's published range from the same payload, and
 * the summary overload of {@code withDecisionSignals} passed a literal null
 * where the detail overload passed the posting body. Since the sync runs last
 * and overwrites, every text-derived column ended up holding a default.
 *
 * <p>These tests pin the two halves of the repair: the mapper carries the body
 * and the real pay, and the signal derivation actually reads that body. They are
 * deliberately about the summary type -- asserting the same thing on a detail
 * response would have passed before the fix and proved nothing.
 */
class ExternalJobSyncParsingTest {

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

    private CandidateJobSummaryResponse summarize(GreenhouseJobBoardResponse.GreenhouseJob job) {
        return ReflectionTestUtils.invokeMethod(service, "toGreenhouseSummary", "acme", job);
    }

    private GreenhouseJobBoardResponse.GreenhouseJob posting(String content, GreenhouseJobBoardResponse.GreenhousePayRange pay) {
        GreenhouseJobBoardResponse.GreenhouseJob job = new GreenhouseJobBoardResponse.GreenhouseJob();
        job.setId(4242L);
        job.setTitle("Senior Backend Engineer");
        job.setAbsoluteUrl("https://boards.greenhouse.io/acme/jobs/4242");
        job.setUpdatedAt(OffsetDateTime.now().minusDays(1));
        job.setContent(content);
        if (pay != null) {
            job.setPayInputRanges(List.of(pay));
        }
        return job;
    }

    private GreenhouseJobBoardResponse.GreenhousePayRange payRange(long minCents, long maxCents) {
        GreenhouseJobBoardResponse.GreenhousePayRange range = new GreenhouseJobBoardResponse.GreenhousePayRange();
        range.setMinCents(BigDecimal.valueOf(minCents));
        range.setMaxCents(BigDecimal.valueOf(maxCents));
        range.setCurrencyType("USD");
        return range;
    }

    @Test
    @DisplayName("the persisted mapper carries the posting body")
    void summaryCarriesTheDescription() {
        // Without this every derivation below is computed against null, which is
        // what the sync did on every run.
        CandidateJobSummaryResponse job = summarize(posting("<p>We are hiring a backend engineer.</p>", null));

        assertThat(job.getDescriptionText())
                .as("the body must reach the sync payload, not just the detail response")
                .contains("We are hiring a backend engineer");
    }

    @Test
    @DisplayName("the employer's published pay range survives into the sync payload")
    void summaryDerivesRealPay() {
        // Previously a string literal, so a posting with a published range was
        // persisted as though the employer had published nothing.
        CandidateJobSummaryResponse job = summarize(posting("<p>Backend role.</p>", payRange(18_000_000L, 23_000_000L)));

        assertThat(job.getSalaryLabel()).isEqualTo("USD $180k-$230k");
        assertThat(job.getTotalCompLabel()).isNotEqualTo("Benchmark needed");
        assertThat(job.getCompensationConfidence()).isEqualTo("POSTED_BASE");
    }

    @Test
    @DisplayName("a posting with no published pay is still reported as unlisted")
    void summaryWithoutPayStaysHonest() {
        // The repair must not invent a number when the employer gave none.
        CandidateJobSummaryResponse job = summarize(posting("<p>Backend role.</p>", null));

        assertThat(job.getSalaryLabel()).isEqualTo("Salary not listed");
        assertThat(job.getCompensationConfidence()).isEqualTo("NEEDS_BENCHMARK");
    }

    @Test
    @DisplayName("sponsorship is read from the body on the write path")
    void sponsorshipIsDerivedOnTheWritePath() {
        CandidateJobSummaryResponse refused = summarize(posting(
                "<p>We do not provide visa sponsorship for this position.</p>", null));
        assertThat(refused.getSponsorshipLanguage()).isEqualTo("NO_SPONSORSHIP");

        CandidateJobSummaryResponse offered = summarize(posting(
                "<p>We offer visa sponsorship for exceptional candidates.</p>", null));
        assertThat(offered.getSponsorshipLanguage()).isEqualTo("SPONSORS");

        CandidateJobSummaryResponse silent = summarize(posting("<p>Backend role.</p>", null));
        assertThat(silent.getSponsorshipLanguage())
                .as("silence must stay UNKNOWN rather than be read as either answer")
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("a refusal is never scored as an offer")
    void refusalIsNotReadAsAnOffer() {
        // The refusal phrasing contains the word "sponsorship", so an order-dependent
        // check could classify it as a positive. This is the case where being wrong
        // costs a visa-dependent candidate a real opportunity.
        for (String phrasing : List.of(
                "Applicants must be authorized to work in the US without sponsorship.",
                "We are unable to sponsor or take over sponsorship of an employment visa.",
                "This role does not sponsor work visas now or in the future.")) {
            assertThat(summarize(posting("<p>" + phrasing + "</p>", null)).getSponsorshipLanguage())
                    .as("phrasing: %s", phrasing)
                    .isEqualTo("NO_SPONSORSHIP");
        }
    }

    @Test
    @DisplayName("required experience is read from the body on the write path")
    void experienceIsDerivedOnTheWritePath() {
        // The title says "Senior" and the body says 9 years. Before the fix the
        // body was unreachable here, so years came only from the title label.
        CandidateJobSummaryResponse job = summarize(posting(
                "<p>You have 9+ years of professional backend experience.</p>", null));

        assertThat(job.getExperienceYears()).isEqualTo(9);
        assertThat(job.getSeniorityLabel()).isNotNull();
    }
}
