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
 * A seniority gap ranks a posting down and says so. It never hides one.
 *
 * <p>The seniority check used to be a filter, keyed on title words against
 * years computed from resume entries. It removed "Staff Accountant" from a staff
 * accountant with four years and "Staff Nurse" from a nurse with six, because
 * "staff" was read as a level everywhere, and it removed nearly every "manager"
 * posting from anyone under eight years. Nothing on the page said so. It
 * reached few people while most resumes parsed to unknown years, and nearly
 * everyone once they parsed.
 *
 * <p>Titles are live postings. Each profile has one experience entry, so its
 * years are exact.
 */
class SeniorityGapRankingTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class), mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
            mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
            mock(UserRepository.class), new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 2000, 0, 1);

    // Spelled out rather than imported, so this file also compiles against the
    // code before the change and fails there on its assertions.
    private static final String ABOVE_YOUR_LEVEL = "Seniority may not fit: more senior than your experience";
    private static final String BELOW_YOUR_LEVEL = "Seniority may not fit: below your experience level";

    @Test
    @DisplayName("staff is the working title for accountants, nurses and pharmacists, not a level")
    void staffIsNotALevelOutsideTechLadders() {
        assertNoGap(profile("Staff Accountant", years(4)),
                "Staff Accountant", "Senior Staff Accountant", "Staff Accountant - Revenue");
        assertNoGap(profile("Registered Nurse", years(6)),
                "Staff Nurse - Emergency Department", "Acute Care Staff Nurse",
                "Clinical Staff Pharmacist - PRN- Day/Evening - DCMC");
        assertNoGap(profile("Attorney", years(3)), "Staff Attorney");
    }

    @Test
    @DisplayName("a staff engineer is shown to a four-year engineer, below the senior role, with the reason")
    void staffEngineerRanksBelowAndSaysWhy() {
        List<CandidateJobSummaryResponse> ranked = rank(profile("Software Engineer", years(4)),
                "Senior Software Engineer", "Staff Software Engineer", "Staff Backend Engineer",
                "Staff Data Scientist");

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .as("the old filter removed every staff title for anyone under seven years")
                .contains("Staff Software Engineer", "Staff Backend Engineer", "Staff Data Scientist");
        assertThat(byTitle(ranked, "Staff Software Engineer").getMatchReasons()).contains(ABOVE_YOUR_LEVEL);
        assertThat(byTitle(ranked, "Staff Software Engineer").getMatchScore())
                .isLessThan(byTitle(ranked, "Senior Software Engineer").getMatchScore());
        assertThat(byTitle(ranked, "Senior Software Engineer").getMatchReasons())
                .noneMatch(reason -> reason.startsWith("Seniority"));
    }

    @Test
    @DisplayName("entry-level and intern titles are shown to an experienced candidate, ranked below")
    void belowLevelRanksBelowAndSaysWhy() {
        List<CandidateJobSummaryResponse> ranked = rank(profile("Data Analyst", years(6)),
                "Data Analyst", "Junior Data Analyst", "Data Analyst I", "Data Analyst Intern");

        for (String title : List.of("Junior Data Analyst", "Data Analyst I", "Data Analyst Intern")) {
            assertThat(byTitle(ranked, title).getMatchReasons()).as(title).contains(BELOW_YOUR_LEVEL);
            assertThat(byTitle(ranked, title).getMatchScore()).as(title)
                    .isLessThan(byTitle(ranked, "Data Analyst").getMatchScore());
        }
    }

    /**
     * 3,312 active manager postings state a minimum; the median asks for 5
     * years and 13% for 8 or more, so six years meets most of them. Assistant
     * managers ask for a median of 2, so they are not read as a level at all.
     */
    @Test
    @DisplayName("a manager posting is in reach at six years, and a director posting is not")
    void managerThresholdFollowsWhatPostingsAsk() {
        List<CandidateJobSummaryResponse> ranked = rank(profile("Warehouse", years(6)),
                "Warehouse Associate", "Warehouse Manager", "Assistant Store Manager",
                "Director of Warehouse Operations");

        assertThat(byTitle(ranked, "Warehouse Manager").getMatchReasons())
                .noneMatch(reason -> reason.startsWith("Seniority"));
        assertThat(byTitle(ranked, "Assistant Store Manager").getMatchReasons())
                .noneMatch(reason -> reason.startsWith("Seniority"));
        assertThat(byTitle(ranked, "Director of Warehouse Operations").getMatchReasons())
                .contains(ABOVE_YOUR_LEVEL);

        List<CandidateJobSummaryResponse> early = rank(profile("Warehouse", years(2)),
                "Warehouse Manager", "Assistant Store Manager");
        assertThat(byTitle(early, "Warehouse Manager").getMatchReasons()).contains(ABOVE_YOUR_LEVEL);
        assertThat(byTitle(early, "Assistant Store Manager").getMatchReasons())
                .noneMatch(reason -> reason.startsWith("Seniority"));
    }

    @Test
    @DisplayName("unknown years make no seniority claim and hide nothing")
    void unknownYearsMakeNoClaim() {
        CandidateProfile noYears = CandidateProfile.builder()
                .matchPreferences(Json.of("{\"targetRoles\":[\"Software engineer\"]}"))
                .experience(Json.of("[{\"company\":\"Acme\",\"title\":\"Software Engineer\"}]"))
                .build();
        String[] titles = {"Staff Software Engineer", "Software Engineer Intern", "Junior Software Engineer"};
        List<CandidateJobSummaryResponse> ranked = rank(noYears, titles);

        assertThat(ranked).as("unknown years").hasSize(titles.length);
        assertThat(ranked).allSatisfy(job -> assertThat(job.getMatchReasons())
                .noneMatch(reason -> reason.startsWith("Seniority")));

        // The control: the same postings for someone with years do carry it,
        // which is the half that fails while the filter still hides them.
        List<CandidateJobSummaryResponse> withYears = rank(profile("Software Engineer", years(5)), titles);
        assertThat(withYears).as("five years: shown, not hidden").hasSize(titles.length);
        assertThat(byTitle(withYears, "Staff Software Engineer").getMatchReasons()).contains(ABOVE_YOUR_LEVEL);
    }

    /**
     * The portal sorts reasons into fits and cautions by wording, and lists
     * anything it does not recognise as a caution under "why this fits".
     */
    @Test
    @DisplayName("the seniority reasons read as cautions in the portal")
    void reasonsAreWordedAsCautions() {
        assertThat(List.of(ABOVE_YOUR_LEVEL, BELOW_YOUR_LEVEL))
                .allSatisfy(reason -> assertThat(reason.toLowerCase()).contains("may not"));

        List<CandidateJobSummaryResponse> ranked = rank(profile("Software Engineer", years(3)),
                "Principal Software Engineer");
        assertThat(byTitle(ranked, "Principal Software Engineer").getMatchReasons())
                .as("listed early enough to survive the six-reason cut")
                .startsWith("Role fit: Software engineer", ABOVE_YOUR_LEVEL);
    }

    private void assertNoGap(CandidateProfile profile, String... titles) {
        List<CandidateJobSummaryResponse> ranked = rank(profile, titles);
        for (String title : titles) {
            assertThat(byTitle(ranked, title).getMatchReasons())
                    .as("\"%s\"", title)
                    .noneMatch(reason -> reason.startsWith("Seniority"));
        }
    }

    private static String years(int count) {
        return OffsetDateTime.now().minusYears(count).minusMonths(1).toLocalDate().toString().substring(0, 7);
    }

    private CandidateProfile profile(String role, String startedYearMonth) {
        return CandidateProfile.builder()
                .matchPreferences(Json.of("{\"targetRoles\":[\"" + role + "\"]}"))
                .experience(Json.of("[{\"company\":\"Acme\",\"title\":\"" + role + "\",\"startDate\":\""
                        + startedYearMonth + "\",\"endDate\":\"Present\",\"current\":true}]"))
                .build();
    }

    private List<CandidateJobSummaryResponse> rank(CandidateProfile profile, String... titles) {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        List<CandidateJobSummaryResponse> jobs = java.util.Arrays.stream(titles).map(this::job).toList();
        List<CandidateJobSummaryResponse> ranked =
                ReflectionTestUtils.invokeMethod(service, "rankPersonalizedJobs", jobs, context);
        return ranked == null ? List.of() : ranked;
    }

    private CandidateJobSummaryResponse byTitle(List<CandidateJobSummaryResponse> jobs, String title) {
        return jobs.stream()
                .filter(job -> title.equals(job.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("\"" + title + "\" was hidden; ranked: "
                        + jobs.stream().map(CandidateJobSummaryResponse::getTitle).toList()));
    }

    private CandidateJobSummaryResponse job(String title) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:test:" + title.hashCode())
                .sourceType("GREENHOUSE").sourceName("Greenhouse").sourceBoardToken("test")
                .externalJobId(String.valueOf(title.hashCode())).title(title).companyName("TestCo")
                .location("Columbus, OH").employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now()).jobQualityScore(80).tags(List.of())
                .build();
    }
}
