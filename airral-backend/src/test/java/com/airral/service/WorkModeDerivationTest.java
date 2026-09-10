package com.airral.service;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Work mode read out of the posting body, without ever claiming more than it says.
 *
 * <p>74% of the catalogue held UNKNOWN, and work mode is one of the two filters a
 * candidate reaches for first. The derivation only ever looked at the title and
 * the location, so Greenhouse -- which publishes no work-mode field at all --
 * resolved about a third of its postings and the rest said nothing. The body was
 * already being fetched for sponsorship, seniority and experience years; it was
 * simply never read for this.
 *
 * <p>The rule these tests hold is the precedence, not the vocabulary. A source
 * that states its own work mode is the most reliable signal in the system --
 * Ashby resolves about 95% of its postings from one flag -- so the body is a last
 * resort and a text guess must never overwrite a stated value. Reading the body
 * first would make the product worse, not better.
 *
 * <p>The second rule is that a miss is honest and a wrong REMOTE is not. The
 * filter is a hard SQL predicate, {@code work_mode = 'REMOTE'}, so whatever
 * reaches that column is what a candidate filtering for remote is shown. Half the
 * cases below are negations and employer-culture copy that must NOT move a
 * posting, because the word "remote" appears in about a sixth of real bodies and
 * hardly any of those postings are remote. They are taken from live postings, as
 * are the ONSITE and HYBRID sentences: measured over 1,394 live postings holding
 * 1,231 UNKNOWN, this resolves 399 of them, and every one of the 41 that became
 * REMOTE was read by hand.
 */
class WorkModeDerivationTest {

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

    private String fromBody(String descriptionText) {
        return ReflectionTestUtils.invokeMethod(service, "workModeFromDescription", descriptionText);
    }

    /** Runs the same fill the read and sync paths run, so precedence is exercised, not simulated. */
    private String applied(String startingWorkMode, String descriptionText) {
        CandidateJobSummaryResponse job = CandidateJobSummaryResponse.builder()
                .title("Software Engineer")
                .location("Boston, MA")
                .workMode(startingWorkMode)
                .build();
        ReflectionTestUtils.invokeMethod(service, "applyWorkModeSignal", job, descriptionText);
        return job.getWorkMode();
    }

    // ---------------------------------------------------------------- precedence

    @Test
    @DisplayName("a work mode the source stated survives a body that says otherwise")
    void statedValuesAreNeverOverwritten() {
        String hybridBody = "This role is based out of our Seattle office and follows a hybrid schedule.";

        // Ashby's isRemote flag and Workable's workplaceType land here as REMOTE
        // and ONSITE. Neither may be talked out of it by prose.
        assertThat(applied("REMOTE", hybridBody)).isEqualTo("REMOTE");
        assertThat(applied("ONSITE", hybridBody)).isEqualTo("ONSITE");
        assertThat(applied("HYBRID", "Work Location: This is a remote / work-from-home role."))
                .isEqualTo("HYBRID");
    }

    @Test
    @DisplayName("only UNKNOWN, blank and null are filled in")
    void onlyUnresolvedValuesAreFilled() {
        String body = "Work Location: This is a remote / work-from-home role.";

        assertThat(applied("UNKNOWN", body)).isEqualTo("REMOTE");
        assertThat(applied("unknown", body)).isEqualTo("REMOTE");
        assertThat(applied(null, body)).isEqualTo("REMOTE");
        assertThat(applied("", body)).isEqualTo("REMOTE");
    }

    @Test
    @DisplayName("a body that says nothing leaves UNKNOWN alone rather than guessing")
    void silenceStaysUnknown() {
        assertThat(applied("UNKNOWN", "We are looking for a Senior Engineer to join our platform team."))
                .isEqualTo("UNKNOWN");
        assertThat(applied("UNKNOWN", "   ")).isEqualTo("UNKNOWN");

        // A posting whose body was never hydrated reaches the derivation as null.
        assertThat(fromBody(null)).isNull();
        assertThat(fromBody("")).isNull();
    }

    @Test
    @DisplayName("the detail path fills the same way the summary path does")
    void detailPathIsFilledToo() {
        CandidateJobDetailResponse detail = CandidateJobDetailResponse.builder()
                .title("Software Engineer")
                .workMode("UNKNOWN")
                .build();
        ReflectionTestUtils.invokeMethod(service, "applyWorkModeSignal", detail, "#LI-Remote");
        assertThat(detail.getWorkMode()).isEqualTo("REMOTE");

        CandidateJobDetailResponse stated = CandidateJobDetailResponse.builder()
                .title("Software Engineer")
                .workMode("ONSITE")
                .build();
        ReflectionTestUtils.invokeMethod(service, "applyWorkModeSignal", stated, "#LI-Remote");
        assertThat(stated.getWorkMode()).isEqualTo("ONSITE");
    }

    // ------------------------------------------------------------------- negation

    @Test
    @DisplayName("a negated remote is not a remote job")
    void negatedRemoteIsNotRemote() {
        assertThat(fromBody("This role is not remote.")).isNull();
        assertThat(fromBody("This role is not a remote position.")).isNull();
        assertThat(fromBody("No remote work available.")).isNull();
        assertThat(fromBody("Remote work is not an option for this position.")).isNull();
        assertThat(fromBody("Remote work is not available for this role.")).isNull();
        assertThat(fromBody("This position cannot be performed remotely.")).isNull();
    }

    @Test
    @DisplayName("a remote employer is not a remote job")
    void employerCultureIsNotAClaimAboutTheRole() {
        // The trap this classifier exists to avoid: an About Us block above an
        // on-site requisition.
        assertThat(fromBody("We are a remote-first company. This position is based in our Austin office."))
                .isNull();
        assertThat(fromBody("Join our remote-friendly culture and grow with us.")).isNull();
        assertThat(fromBody("During the pandemic we went remote, and we learned a lot.")).isNull();
        assertThat(fromBody("Benefits include a remote work stipend and a home office allowance."))
                .isNull();

        // Two employers in the live catalogue carry this legal notice verbatim.
        // A bare "remote job" token read it as an offer of remote work.
        assertThat(fromBody(
                "Notice to Applicants for Jobs Located in NYC or Remote Jobs Associated With Office in NYC Only."))
                .isNull();
    }

    @Test
    @DisplayName("the negation cannot reach out of its own sentence")
    void negationIsBoundedBySentence() {
        // The refusal belongs to the paragraph above; the requisition's own line
        // still has to be read.
        assertThat(fromBody("Relocation is not offered. Work Location: This is a remote role."))
                .isEqualTo("REMOTE");
    }

    // ---------------------------------------------------------------------- remote

    @Test
    @DisplayName("a role-scoped statement of remote is taken")
    void roleScopedRemoteIsTaken() {
        assertThat(fromBody("Work Location: This is a remote / work-from-home role.")).isEqualTo("REMOTE");
        assertThat(fromBody("This role is fully remote within the United States, with up to 50% travel."))
                .isEqualTo("REMOTE");
        assertThat(fromBody("What You'll Do Location: Remote - PT or MT Time Zones")).isEqualTo("REMOTE");

        // The LinkedIn workplace tag. A recruiter stamps it on one requisition, so
        // unlike every other form here it cannot be company copy.
        assertThat(fromBody("Cloud First Security #LI-Remote P9611_3543793")).isEqualTo("REMOTE");
    }

    // ---------------------------------------------------------------------- hybrid

    @Test
    @DisplayName("a role-scoped statement of hybrid is taken")
    void roleScopedHybridIsTaken() {
        assertThat(fromBody("#LI-Hybrid")).isEqualTo("HYBRID");
        assertThat(fromBody("This role is based out of our Seattle office and follows a hybrid schedule."))
                .isEqualTo("HYBRID");
        assertThat(fromBody("You must be located in San Francisco for this hybrid position."))
                .isEqualTo("HYBRID");
        assertThat(fromBody("Location: McLean, VA | Work Model: In-Office / Hybrid")).isEqualTo("HYBRID");
    }

    @Test
    @DisplayName("a hybrid employer, and a hybrid of two jobs, are not a hybrid schedule")
    void hybridNeedsToBeAboutAPlace() {
        // Both appear verbatim on the fully remote requisitions of the same
        // employers, so neither says anything about this role.
        assertThat(fromBody("We operate as a hybrid workplace to ensure our people can create work-life harmony."))
                .isNull();
        assertThat(fromBody("We embrace a hybrid work model that fosters in-person collaboration."))
                .isNull();

        // "hybrid" also has an ordinary English meaning, and employers use it.
        // Both of these are live postings; neither is about where the work happens.
        assertThat(fromBody("This is a hybrid role: part strategic people leader, part expert practitioner."))
                .isNull();
        assertThat(fromBody("This is a hybrid role - roughly half dedicated to Chief of Staff "
                + "responsibilities and the remainder to special projects."))
                .isNull();
    }

    // ------------------------------------------------------------- days in office

    @Test
    @DisplayName("days a week in the office separate hybrid from on-site")
    void dayCountsSplitHybridFromOnsite() {
        assertThat(fromBody("This role is based in our New York, NY office, with in-person attendance "
                + "expected at least 3 days per week."))
                .isEqualTo("HYBRID");
        assertThat(fromBody("Location: On-site 4x a week (Tuesday-Friday) at an Axon Hub location."))
                .isEqualTo("HYBRID");
        assertThat(fromBody("This role is based in our New York, NY office, with in-person attendance "
                + "expected 5 days per week."))
                .isEqualTo("ONSITE");
        assertThat(fromBody("This role will be required to be in office 5x a week.")).isEqualTo("ONSITE");
    }

    @Test
    @DisplayName("a count of days per month is not a count of days per week")
    void dayCountsMustBeWeekly() {
        // Measured: this sentence read as four days a week and called a role
        // whose own body opens "The role is primarily remote" hybrid.
        assertThat(fromBody("Approximate onsite expectation of 3-4 days per month minimum.")).isNull();
        assertThat(fromBody("Expect to be onsite roughly 4 days per quarter.")).isNull();
    }

    // ---------------------------------------------------------------------- onsite

    @Test
    @DisplayName("onsite is claimed only when the posting says so")
    void onsiteNeedsAnExplicitStatement() {
        assertThat(fromBody("Must work on-site at HQ in Costa Mesa, CA.")).isEqualTo("ONSITE");
        assertThat(fromBody("Must be able to work on-site at our Santa Ana, CA location.")).isEqualTo("ONSITE");
        assertThat(fromBody("This position is fully onsite.")).isEqualTo("ONSITE");
    }

    @Test
    @DisplayName("the absence of remote language is not a statement of on-site")
    void absenceOfRemoteIsNotOnsite() {
        // The assumption inferWorkMode was deliberately changed to stop making,
        // after it filed 1331 of 1368 postings as on-site.
        assertThat(fromBody("We are hiring a Staff Engineer for our payments team in Chicago, IL. "
                + "You will work closely with product and design.")).isNull();
        assertThat(fromBody("At Robinhood, we believe in the power of in-person work to accelerate "
                + "progress, spark innovation, and strengthen community.")).isNull();
        assertThat(fromBody("Our office is at 1 Market Street, San Francisco, CA.")).isNull();
    }
    @Test
    @DisplayName("a refusal aimed at hybrid or onsite is read as a refusal, not a claim")
    void placeClaimsHonourNegation() {
        // Every one of these was ACCEPTED before a review caught it, on text taken
        // from live postings. The remote claim was guarded twice and these two were
        // not guarded at all, so the posting said the opposite of what we recorded.
        assertThat(fromBody("This is not a hybrid position - the role is fully onsite at our New York office."))
                .isNotEqualTo("HYBRID");
        assertThat(fromBody("We do not offer a hybrid role. You will be based in our Austin office."))
                .isNotEqualTo("HYBRID");
        assertThat(fromBody("This role is not fully onsite.")).isNotEqualTo("ONSITE");
        assertThat(fromBody("We do not offer a hybrid schedule for this position.")).isNotEqualTo("HYBRID");
    }

    @Test
    @DisplayName("a day count is not read across a line break")
    void dayCountStaysInsideItsOwnLine() {
        // "In-office amenities" is a heading. The gym's opening hours on the next
        // line are not the number of days this job is in the office, but the gap
        // matched newlines so the count was picked up from a different sentence --
        // and the day count runs first, so it outranked every other signal.
        assertThat(fromBody("In-office amenities\nOur gym is open 5 days a week for all staff."))
                .isNotEqualTo("ONSITE");
        // The real thing must still resolve.
        assertThat(fromBody("You will be in the office 3 days per week.")).isEqualTo("HYBRID");
        assertThat(fromBody("This role is in-office 5 days per week.")).isEqualTo("ONSITE");
    }

    @Test
    @DisplayName("a genuine claim beside a refusal of a different mode still resolves")
    void aRefusalOfOneModeDoesNotSilenceAnother() {
        // The guard is per sentence, so refusing remote must not also swallow the
        // hybrid statement that follows it.
        assertThat(fromBody("Remote work is not available. This role follows a hybrid schedule."))
                .isEqualTo("HYBRID");
    }

}
