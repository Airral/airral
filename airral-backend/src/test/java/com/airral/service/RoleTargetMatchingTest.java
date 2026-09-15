package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.dto.response.CandidateJobDetailResponse;
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
 * Ranking against the role a candidate actually picked.
 *
 * <p>Onboarding offers the families counted out of the live corpus, so a
 * candidate can now tick "Warehouse" or "Retail". Those labels are then the
 * only thing the matcher knows about them -- the common case is no resume at
 * all. What is guarded here is that answering the question helps.
 *
 * <p>It did the opposite. {@link RoleMatchClassifier} has no family for
 * warehouse, retail, driver, food service or construction work, which together
 * are the largest part of the catalogue. A profile of {@code ["Warehouse"]}
 * therefore classified as UNKNOWN, and an unknown profile is treated as
 * compatible with everything -- so software postings, which do classify,
 * collected a +14 "Role fit: Software Engineering" bonus that no warehouse
 * posting could ever earn. Meanwhile "Order Picker" shares no token with the
 * word "Warehouse", scored 0, and was capped and labelled "Role is outside
 * your target titles".
 *
 * <p>Titles below are live postings. The jobs are otherwise identical, so
 * ordering here is decided by role fit and nothing else.
 */
class RoleTargetMatchingTest {

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

    private static final String OUTSIDE_TARGET = "Role is outside your target titles";

    // ── the feed: unrelated families are removed ─────
    //
    // rankPersonalizedJobs filters before it scores, and that filter was already
    // meant to drop unrelated tracks. It only ever worked for candidates the old
    // classifier had a family for, which excluded every frontline family.

    @Test
    @DisplayName("picking Warehouse puts warehouse work first and drops software work")
    void warehouseTargetRanksWarehouseWorkFirst() {
        List<CandidateJobSummaryResponse> ranked = rank(
                pickedRoles("Warehouse"),
                job("1", "Order Picker", "Order Fulfillment"),
                job("2", "Staff Software Engineer", "Engineering"),
                job("3", "Fulfillment Associate", "Operations"));

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .as("a software posting is not warehouse work")
                .doesNotContain("Staff Software Engineer");
        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .containsExactlyInAnyOrder("Order Picker", "Fulfillment Associate");

        assertThat(byTitle(ranked, "Order Picker").getMatchReasons())
                .as("Order Picker is warehouse work; saying otherwise is a false statement about the job")
                .noneMatch(OUTSIDE_TARGET::equals);
        assertThat(byTitle(ranked, "Order Picker").getMatchReasons())
                .anyMatch(reason -> reason.startsWith("Role fit:"));
    }

    @Test
    @DisplayName("retail and customer service are near neighbours, not strangers")
    void adjacentFamilyIsKeptAndUnrelatedIsNot() {
        List<CandidateJobSummaryResponse> ranked = rank(
                pickedRoles("Retail"),
                job("1", "Customer Service Representative", "Support"),
                job("2", "Senior Software Engineer", "Engineering"),
                job("3", "Retail Sales Associate", "Stores"));

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .as("someone who wants retail work is close to customer service and far from software")
                .containsExactlyInAnyOrder("Retail Sales Associate", "Customer Service Representative");
        assertThat(ranked.get(0).getTitle()).isEqualTo("Retail Sales Associate");

        assertThat(byTitle(ranked, "Customer Service Representative").getMatchReasons())
                .anyMatch(reason -> reason.startsWith("Near your target:"));
    }

    /**
     * The 13.6% of live titles no family fits.
     *
     * <p>"Closing Team Leader" is a real title that places nowhere. Keeping it is
     * what stops a narrow family from emptying the feed, and it is the same call
     * the catalogue count makes when it reports unplaced postings rather than
     * pushing them into the nearest bucket.
     */
    @Test
    @DisplayName("an unplaceable title is kept and makes no claim either way")
    void unplaceableTitleIsKeptAndMakesNoClaim() {
        List<CandidateJobSummaryResponse> ranked = rank(
                pickedRoles("Warehouse"),
                job("1", "Closing Team Leader", null),
                job("2", "Order Picker", "Order Fulfillment"));

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .as("a title we could not read is not evidence the job is wrong for them")
                .contains("Closing Team Leader");
        assertThat(byTitle(ranked, "Closing Team Leader").getMatchReasons())
                .as("we cannot place this title, so we cannot say it is outside the target")
                .noneMatch(OUTSIDE_TARGET::equals);
    }

    @Test
    @DisplayName("the candidate's own wording in the title still wins")
    void statedWordingBeatsFamilyInference() {
        // The family tiers must not override wording the candidate typed, or
        // someone specific would be pulled back toward the broad family we
        // guessed for them.
        List<CandidateJobSummaryResponse> ranked = rank(
                pickedRoles("Diesel Technician"),
                job("1", "Diesel Technician", "Fleet"),
                job("2", "Automotive Technician", "Service"));

        assertThat(ranked.get(0).getTitle()).isEqualTo("Diesel Technician");
        assertThat(byTitle(ranked, "Diesel Technician").getMatchReasons())
                .anyMatch(reason -> reason.contains("Diesel Technician"));
    }

    // ── the detail page: no filter, so the words have to be right ─────
    //
    // A candidate can open any posting by link or from search, and that path
    // scores without filtering. It is where a false explanation actually reaches
    // someone, so the sentences are pinned here rather than on the feed.

    @Test
    @DisplayName("a software posting cannot claim role fit for a warehouse candidate")
    void unrelatedFamilyDoesNotClaimRoleFit() {
        List<String> reasons = detailReasons(pickedRoles("Warehouse"), "Staff Software Engineer", "Engineering");

        assertThat(reasons)
                .as("the candidate never said anything that supports a software role fit")
                .noneMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(reasons)
                .as("both sides classify here, so the mismatch is a fact and should be said")
                .contains(OUTSIDE_TARGET);
    }

    @Test
    @DisplayName("an unplaceable title is not declared outside the target")
    void unplaceableTitleIsNotDeclaredOutside() {
        assertThat(detailReasons(pickedRoles("Warehouse"), "Closing Team Leader", null))
                .noneMatch(OUTSIDE_TARGET::equals);
    }

    /**
     * The card cannot say two opposite things at once.
     *
     * <p>A candidate targeting Store security and a "Security Engineer" posting
     * share the word "security", which is one token of a two-token target: enough
     * for partial credit, not enough to clear the threshold the caller uses. So
     * the posting carried "Role fit: Store security" and "Role is outside your
     * target titles" together.
     */
    @Test
    @DisplayName("a shared word does not earn role fit against a family that disagrees")
    void contradictoryReasonsAreNotBothShown() {
        List<String> reasons = detailReasons(pickedRoles("Store security"), "Security Engineer", "Engineering");

        assertThat(reasons)
                .as("one word in common is not a role fit when the families disagree")
                .noneMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(reasons).contains(OUTSIDE_TARGET);

        assertThat(detailReasons(pickedRoles("Store security"), "Asset Protection Specialist", "Stores"))
                .anyMatch(reason -> reason.startsWith("Role fit:"));
    }

    /**
     * A retail "Front End" department is the checkout lanes, not web development.
     *
     * <p>The old classifier read the bare phrase "front end" as software, so a
     * cashier posting shown to a software candidate carried the reason "Role fit:
     * Software Engineering".
     */
    @Test
    @DisplayName("a cashier is not a software engineering role fit")
    void cashierUnderFrontEndDepartmentIsNotSoftware() {
        List<String> reasons = detailReasons(pickedRoles("Software engineer"), "Cashier", "Front End");

        assertThat(reasons).noneMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(reasons).contains(OUTSIDE_TARGET);
    }

    // ── fixtures ─────────────────────────────────────

    /** What onboarding stores: family labels, no resume, no skills. */
    private CandidateProfile pickedRoles(String... roles) {
        String json = String.join(",", List.of(roles).stream().map(r -> '"' + r + '"').toList());
        return CandidateProfile.builder()
                .matchPreferences(Json.of("{\"targetRoles\":[" + json + "]}"))
                .build();
    }

    /** The unfiltered path: one posting opened directly, scored and explained. */
    private List<String> detailReasons(CandidateProfile profile, String title, String department) {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        CandidateJobDetailResponse detail = CandidateJobDetailResponse.builder()
                .jobId("GREENHOUSE:test:detail")
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken("test")
                .externalJobId("detail")
                .title(title)
                .companyName("TestCo")
                .department(department)
                .location("Columbus, OH")
                .employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now())
                .jobQualityScore(80)
                .tags(List.of())
                .build();
        CandidateJobDetailResponse scored =
                ReflectionTestUtils.invokeMethod(service, "applyCandidateMatch", detail, context);
        return scored == null || scored.getMatchReasons() == null ? List.of() : scored.getMatchReasons();
    }

    private List<CandidateJobSummaryResponse> rank(CandidateProfile profile, CandidateJobSummaryResponse... jobs) {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", profile);
        List<CandidateJobSummaryResponse> ranked = ReflectionTestUtils.invokeMethod(
                service, "rankPersonalizedJobs", List.of(jobs), context);
        return ranked == null ? List.of() : ranked;
    }

    private CandidateJobSummaryResponse byTitle(List<CandidateJobSummaryResponse> jobs, String title) {
        return jobs.stream()
                .filter(job -> title.equals(job.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "\"" + title + "\" was filtered out entirely; ranked: "
                                + jobs.stream().map(CandidateJobSummaryResponse::getTitle).toList()));
    }

    private CandidateJobSummaryResponse job(String id, String title, String department) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:test:" + id)
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken("test")
                .externalJobId(id)
                .title(title)
                .companyName("TestCo")
                .department(department)
                .location("Columbus, OH")
                .employmentType("Full-time")
                .sourceUpdatedAt(OffsetDateTime.now())
                .jobQualityScore(80)
                .tags(List.of())
                .build();
    }
}
