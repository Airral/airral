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
 * "Role fit" is said only when the title names the job the candidate asked for.
 *
 * <p>A signed-in candidate with the target "Data analyst" opened their feed to
 * Roblox's "Software Engineer, Data Access", "Senior Data Scientist, Engine
 * Infra" and "Senior Software Engineer - Data Infrastructure, Safety" at the
 * top, each labelled "Role fit: Data Analyst" at 76-77%. The role matcher drops
 * generic words before comparing, and "analyst" was one of them, so the whole
 * target came down to the word "data". Every title containing it scored as if
 * it named the target, while "Business Intelligence Analyst" and "Senior
 * Analyst, Sales Analytics", which do not contain "data", got no reason at all.
 *
 * <p>Titles are live postings. The jobs are otherwise identical, so the order
 * is decided by role fit alone.
 */
class RoleNounFitTest {

    private final CandidateJobSearchService service = new CandidateJobSearchService(
            mock(ExternalJobPostingStore.class), mock(GreenhouseJobBoardClient.class),
            mock(LeverJobBoardClient.class), mock(AshbyJobBoardClient.class),
            mock(SmartRecruitersJobBoardClient.class), mock(WorkableJobBoardClient.class),
            mock(WorkdayJobBoardClient.class), mock(BambooHrJobBoardClient.class),
            mock(CareerPageJobBoardClient.class), mock(CandidateProfileRepository.class),
            mock(UserRepository.class), new ObjectMapper(),
            "airbnb", "", "", "", "", "", "", "", "", "", "", "US", 45, 2000, 0, 1);

    private static final List<String> ROBLOX_REPORT = List.of(
            "Software Engineer, Data Access",
            "Senior Data Scientist, Engine Infra",
            "Senior Software Engineer - Data Infrastructure, Safety");

    private static final List<String> ANALYST_TITLES = List.of(
            "Data Analyst",
            "Senior Data Analyst",
            "Business Intelligence Analyst",
            "Analytics Analyst",
            "Reporting Analyst",
            "Senior Analyst, Sales Analytics",
            "Product Analyst",
            "Compliance Data & Reporting Analyst");

    /** The reported candidate, as their profile was stored. */
    private CandidateProfile remoteDataAnalyst() {
        return CandidateProfile.builder()
                .headline("Data Analyst")
                .preferredWorkMode("REMOTE")
                .skills(Json.of("[\"SQL\",\"Tableau\",\"Snowflake\",\"Excel\",\"PostgreSQL\"]"))
                .experience(Json.of("[{\"company\":\"Acme\",\"title\":\"Data Analyst\","
                        + "\"startDate\":\"2021-09\",\"endDate\":\"Present\",\"current\":true}]"))
                .matchPreferences(Json.of("{\"targetRoles\":[\"Data analyst\"],\"seniority\":\"MID\"}"))
                .build();
    }

    /**
     * Kept as well as silenced. Zeroing the coincidental match would have hidden
     * these: a zero falls past the "scored well enough" check onto the
     * compatibility vote, and that vote rejects software for an analyst.
     */
    @Test
    @DisplayName("software, data science and data engineering titles are kept, but not as a data analyst role fit")
    void dataQualifierIsNotTheRole() {
        List<CandidateJobSummaryResponse> ranked = rank(remoteDataAnalyst(), ROBLOX_REPORT);

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .as("losing the role fit must not hide the posting")
                .containsExactlyInAnyOrderElementsOf(ROBLOX_REPORT);
        for (String title : ROBLOX_REPORT) {
            assertThat(byTitle(ranked, title).getMatchReasons())
                    .as("\"%s\" shares the word data with the target and names a different job", title)
                    .noneMatch(reason -> reason.startsWith("Role fit:"));
        }
    }

    @Test
    @DisplayName("genuine analyst titles carry the role fit, in the candidate's own words")
    void analystTitlesKeepRoleFit() {
        List<CandidateJobSummaryResponse> ranked = rank(remoteDataAnalyst(), concat(ROBLOX_REPORT, ANALYST_TITLES));

        for (String title : ANALYST_TITLES) {
            assertThat(byTitle(ranked, title).getMatchReasons())
                    .as("\"%s\" is analyst work in the analytics family", title)
                    .contains("Role fit: Data Analyst");
        }
    }

    @Test
    @DisplayName("every genuine analyst title ranks above every reported coincidence")
    void analystTitlesOutrankTheCoincidences() {
        List<CandidateJobSummaryResponse> ranked = rank(remoteDataAnalyst(), concat(ROBLOX_REPORT, ANALYST_TITLES));

        int weakestAnalyst = ANALYST_TITLES.stream()
                .mapToInt(title -> byTitle(ranked, title).getMatchScore()).min().orElseThrow();
        int strongestCoincidence = ROBLOX_REPORT.stream()
                .mapToInt(title -> byTitle(ranked, title).getMatchScore()).max().orElseThrow();
        assertThat(weakestAnalyst).isGreaterThan(strongestCoincidence);
    }

    /**
     * The same mechanism, on the other targets it broke. The first list names
     * the job and must keep the reason; the second must not claim it. Two
     * guards ride along: the synonyms the fix must not cost ("Backend
     * Developer" for a backend engineer is the same work), and the analyst-only
     * limit on same-family titles, without which a machine learning engineer
     * was a data engineer's role fit and a merchandising associate a sales
     * associate's.
     */
    @Test
    @DisplayName("the same rule holds for product, sales and data engineering targets")
    void otherTargetsSameMechanism() {
        assertSplit("Product manager",
                List.of("Senior Product Manager, Growth"),
                List.of("Product Designer", "Senior Software Engineer, Product"));
        assertSplit("Sales associate",
                List.of("Sales Floor Associate"),
                List.of("Sales Development Representative", "Sales Manager",
                        "Full Time - Merchandising Service Associate - Day"));
        assertSplit("Data engineer",
                List.of("Software Engineer, Data Platform"),
                List.of("Senior Data Analyst", "Senior Data Scientist", "Data Entry Specialist",
                        "Senior Machine Learning Engineer"));
        assertSplit("Backend engineer", List.of("Backend Developer"), List.of());
        assertSplit("Java developer", List.of("Java Engineer"), List.of());
    }

    private void assertSplit(String target, List<String> namesTheJob, List<String> notTheJob) {
        List<CandidateJobSummaryResponse> ranked = rank(typed(target), concat(namesTheJob, notTheJob));
        // An offered label travels unchanged; free text is title-cased.
        String label = RoleFamilyTaxonomy.pickedLabel(target);
        String reason = "Role fit: " + (label != null ? label : titleCase(target));
        for (String title : namesTheJob) {
            assertThat(byTitle(ranked, title).getMatchReasons())
                    .as("\"%s\" for a candidate targeting \"%s\"", title, target)
                    .contains(reason);
        }
        for (String title : notTheJob) {
            assertThat(byTitle(ranked, title).getMatchReasons())
                    .as("\"%s\" for a candidate targeting \"%s\"", title, target)
                    .noneMatch(r -> r.startsWith("Role fit:"));
        }
    }

    private static String titleCase(String value) {
        return java.util.Arrays.stream(value.split(" "))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private CandidateProfile typed(String role) {
        return CandidateProfile.builder()
                .matchPreferences(Json.of("{\"targetRoles\":[\"" + role + "\"]}"))
                .build();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }

    private List<CandidateJobSummaryResponse> rank(CandidateProfile profile, List<String> titles) {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        List<CandidateJobSummaryResponse> jobs = titles.stream().map(this::job).toList();
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
                .location("Remote US").workMode("REMOTE").employmentType("Full-time")
                .salaryLabel("$120k - $150k")
                .sourceUpdatedAt(OffsetDateTime.now()).jobQualityScore(85).tags(List.of())
                .build();
    }
}
