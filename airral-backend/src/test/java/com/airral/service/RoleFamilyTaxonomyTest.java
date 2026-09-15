package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariants that keep the picker and the ranker telling the same story.
 *
 * <p>These are structural, not behavioural: each one guards a way the taxonomy
 * can stop working without producing a wrong answer anywhere -- only a missing
 * one. That failure mode is the reason this class exists. Onboarding offered a
 * list of role families counted out of the live corpus while the ranker used a
 * different table that had never heard of most of them, and nothing failed. The
 * feed just quietly got worse for anyone who answered the question.
 */
class RoleFamilyTaxonomyTest {

    /**
     * Every option onboarding can show has to be an option the ranker can score.
     *
     * <p>This is the invariant the whole shared-taxonomy change exists to hold.
     * The picker offers {@code RoleFamilyCount.label()} values and stores the
     * label verbatim as the candidate's target role; the ranker reads that
     * string back through {@link RoleFamilyTaxonomy#classifyTerm}. If the round
     * trip does not return the same family, the candidate has expressed a
     * preference that ranks nothing -- the button works, the form saves, and the
     * feed ignores it.
     *
     * <p>Seven labels failed this when it was first written: Store security,
     * Maintenance, Healthcare, Data science, Design, Administrative and
     * Teaching. Store security alone is 348 live postings.
     */
    @Test
    @DisplayName("every offered label classifies back to its own family")
    void everyLabelRoundTrips() {
        List<String> broken = new ArrayList<>();
        for (String label : RoleFamilyTaxonomy.labels()) {
            if (!label.equals(RoleFamilyTaxonomy.classifyTerm(label))) {
                broken.add(label + " -> " + RoleFamilyTaxonomy.classifyTerm(label));
            }
        }
        assertThat(broken)
                .as("a label the ranker cannot read back is an option that ranks nothing")
                .isEmpty();
    }

    /**
     * Read the DECLARATION, not the built map.
     *
     * <p>Two earlier versions of this test iterated {@code labels() x labels()}
     * calling {@code adjacent()}, which proves nothing: {@code buildAdjacency}
     * inserts both directions unconditionally, so a symmetry assertion over the
     * built map can never fail, and every name such a loop returns is in
     * {@code labels()} by construction -- a pair written {@code ("Warehouse",
     * "Drivr")} passed both. A misspelled family is silent: it matches no job and
     * the near-miss score is simply never awarded.
     */
    @Test
    @DisplayName("every declared adjacency names two real, different families")
    void declaredAdjacencyNamesRealFamilies() {
        List<String> labels = RoleFamilyTaxonomy.labels();
        List<List<String>> pairs = RoleFamilyTaxonomy.declaredPairs();
        assertThat(pairs).as("the adjacency table cannot be empty").isNotEmpty();

        List<String> bad = new ArrayList<>();
        for (List<String> pair : pairs) {
            if (pair.size() != 2) {
                bad.add("not a pair: " + pair);
                continue;
            }
            if (!labels.contains(pair.get(0))) {
                bad.add("no such family: " + pair.get(0) + " (in " + pair + ")");
            }
            if (!labels.contains(pair.get(1))) {
                bad.add("no such family: " + pair.get(1) + " (in " + pair + ")");
            }
            if (pair.get(0).equals(pair.get(1))) {
                // Same-family and near-family score differently and print
                // different sentences, so a self-pair would make the weaker of
                // the two reachable for an exact match.
                bad.add("family paired with itself: " + pair.get(0));
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("a declared adjacency holds in both directions")
    void declaredAdjacencyIsUsableBothWays() {
        for (List<String> pair : RoleFamilyTaxonomy.declaredPairs()) {
            assertThat(RoleFamilyTaxonomy.adjacent(pair.get(0), pair.get(1)))
                    .as(pair.get(0) + " -> " + pair.get(1) + " was declared and must resolve")
                    .isTrue();
            assertThat(RoleFamilyTaxonomy.adjacent(pair.get(1), pair.get(0)))
                    .as(pair.get(1) + " -> " + pair.get(0) + " is the same pair read backwards")
                    .isTrue();
        }
    }

    /**
     * The frontline families are one labour market; Retail and Sales are not.
     *
     * <p>Warehouse having only Driver, Manufacturing and Operations meant a
     * warehouse candidate had Cashier, Line Cook, Custodian and Security Officer
     * hidden with "Role is outside your target titles" on each. Retail-to-Sales
     * was the opposite error: sales floor titles classify as Retail, so what is
     * left in Sales is enterprise B2B, and the pairing put "Mid-Market Account
     * Executive" at the top of a retail feed as a near miss.
     */
    @Test
    @DisplayName("entry-level frontline work is mutually adjacent; enterprise sales is not")
    void frontlineWorkIsOneMarket() {
        List<String> frontline = List.of("Warehouse", "Retail", "Food service", "Housekeeping", "Store security");
        for (String a : frontline) {
            for (String b : frontline) {
                if (!a.equals(b)) {
                    assertThat(RoleFamilyTaxonomy.adjacent(a, b))
                            .as(a + " and " + b + " are the same entry-level market")
                            .isTrue();
                }
            }
        }

        assertThat(RoleFamilyTaxonomy.adjacent("Retail", "Sales"))
                .as("Sales holds enterprise B2B; store floor titles are already Retail")
                .isFalse();
        assertThat(RoleFamilyTaxonomy.adjacent("Warehouse", "Software engineer")).isFalse();
        assertThat(RoleFamilyTaxonomy.adjacent("Teaching", "Driver")).isFalse();
    }

    @Test
    @DisplayName("free text is still read the way a posting title would be")
    void freeTextFallsThroughToKeywords() {
        // The label index must not shadow the keyword table for anything a
        // candidate types themselves.
        assertThat(RoleFamilyTaxonomy.classifyTerm("warehouse picker")).isEqualTo("Warehouse");
        assertThat(RoleFamilyTaxonomy.classifyTerm("line cook")).isEqualTo("Food service");
        assertThat(RoleFamilyTaxonomy.classifyTerm("registered nurse")).isEqualTo("Healthcare");
        assertThat(RoleFamilyTaxonomy.classifyTerm("backend developer")).isEqualTo("Software engineer");

        // And text that fits nothing still returns null rather than the nearest
        // bucket, on both paths.
        assertThat(RoleFamilyTaxonomy.classifyTerm("MST ASM")).isNull();
        assertThat(RoleFamilyTaxonomy.classifyTerm(null)).isNull();
        assertThat(RoleFamilyTaxonomy.classifyTerm("   ")).isNull();
    }

    /**
     * A retail "Front End" department is the checkout lanes, not web development.
     *
     * <p>Pinned on both tables because they disagreed, and the disagreement was
     * user-visible: a cashier posting was shown to a software candidate carrying
     * the reason "Role fit: Software Engineering".
     */
    @Test
    @DisplayName("front end means the front of the store unless it says engineer")
    void frontEndDepartmentIsNotSoftware() {
        assertThat(RoleFamilyTaxonomy.classify("Cashier", "Front End")).isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Front End Engineer", "Engineering"))
                .isEqualTo("Software engineer");

        // The mirror case: a professional title carrying a domain word that an
        // earlier family claims. "Staff Software Engineer, Clinical Fit" was
        // Healthcare, on Healthcare's "clinical", and reached the top of a
        // Healthcare candidate's feed as "Role fit: Healthcare".
        assertThat(RoleFamilyTaxonomy.classify("Staff Software Engineer, Clinical Fit", null))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Software Engineer, Restaurant Platform", null))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Data Scientist, Patient Outcomes", null))
                .isEqualTo("Data science");

        // But the frontline-first ordering it sits in front of still holds.
        assertThat(RoleFamilyTaxonomy.classify("Sales Associate - Building Materials", null))
                .isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Target Security Specialist", null))
                .isEqualTo("Store security");
        assertThat(RoleFamilyTaxonomy.classify("Senior Project Manager - Data Center Construction", null))
                .as("measured: priority-listing project manager took nine of these from Construction")
                .isEqualTo("Construction");

        RoleMatchClassifier classifier = new RoleMatchClassifier();
        assertThat(classifier.classifyJob("Cashier", "Front End", null).families())
                .doesNotContain(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(classifier.classifyJob("Frontend Engineer", "Engineering", null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(classifier.classifyJob("Front End Developer", "Engineering", null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
    }

    /**
     * Seven families cannot recognise their own name in a posting title.
     *
     * <p>"Maintenance Worker", "Teaching Assistant" and "Administrative
     * Coordinator" place nowhere, because those families carry only specific
     * entries. Adding the bare labels as keywords was tried and reverted: this
     * table is scanned family by family in order, so a bare label in an early
     * family beats a specific keyword in every later one -- "maintenance" put
     * "Maintenance Data Analyst" in Maintenance ahead of Analytics and
     * "Maintenance Planner" ahead of Operations. It also moves the counts shown
     * during onboarding, which {@link RoleFamilyTaxonomy#classifyTerm} already
     * gives as the reason not to do it.
     *
     * <p>The gap is closed on the candidate side by {@code pickedLabel} and on
     * the retrieval side by {@code RETRIEVAL_SEEDS}, which searches "hvac" and
     * "maintenance technician" rather than the label. This test pins the
     * unplaced answers so that a future attempt to "fix" them has to read the
     * reasoning first.
     */
    @Test
    @DisplayName("a bare family name in a title is unplaced, on purpose")
    void bareFamilyNameIsUnplaced() {
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Worker", null)).isNull();
        assertThat(RoleFamilyTaxonomy.classify("Teaching Assistant", null)).isNull();
        assertThat(RoleFamilyTaxonomy.classify("Administrative Coordinator", null)).isNull();

        // What the bare labels would have cost: each of these belongs to a
        // family later in the table than the one whose label appears in it.
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Data Analyst", null)).isEqualTo("Analytics");
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Planner", null)).isEqualTo("Operations");
        assertThat(RoleFamilyTaxonomy.classify("Data Science Program Manager", null))
                .isEqualTo("Project manager");

        // The specific entries still carry the families themselves.
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Technician", null)).isEqualTo("Maintenance");
        assertThat(RoleFamilyTaxonomy.classify("Registered Nurse", null)).isEqualTo("Healthcare");
        assertThat(RoleFamilyTaxonomy.classify("Executive Assistant", null)).isEqualTo("Administrative");
    }

    /**
     * "front end" belongs to software here, and retail loses the tie.
     *
     * <p>Both placements were tried. With "front end" in Retail, which is
     * scanned first, "Front End Web Developer" and "Front End React Developer"
     * became retail jobs and a software candidate lost them -- and
     * PRIORITY_RULES cannot rescue those, because it matches exact suffixes and
     * any intervening word gets through. Without it, "Front End Associate" and a
     * bare "Front End" department are unplaced.
     *
     * <p>Unplaced is the better failure: a posting we could not read is reported
     * as unread, where a software posting filed under Retail is a wrong answer
     * we would state to a candidate as a reason.
     */
    @Test
    @DisplayName("front end reads as software, and retail front end is unplaced")
    void frontEndReadsAsSoftware() {
        assertThat(RoleFamilyTaxonomy.classify("Front End Engineer", "Engineering"))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Front End Web Developer", null))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Front End React Developer", null))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Front End Lead", "Engineering"))
                .isEqualTo("Software engineer");

        // The accepted cost, pinned so it is a decision and not a surprise.
        assertThat(RoleFamilyTaxonomy.classify("Front End Associate", null)).isNull();
        assertThat(RoleFamilyTaxonomy.classify("Closing Team Leader", "Front End")).isNull();

        // A cashier is still retail, by its own title rather than a department.
        assertThat(RoleFamilyTaxonomy.classify("Cashier", "Front End")).isEqualTo("Retail");

        // The mirror case: a professional title carrying a domain word an
        // earlier family claims. This reached the top of a Healthcare feed as
        // "Role fit: Healthcare", on Healthcare's "clinical".
        assertThat(RoleFamilyTaxonomy.classify("Staff Software Engineer, Clinical Fit", null))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Data Scientist, Patient Outcomes", null))
                .isEqualTo("Data science");

        // And the frontline-first ordering it sits in front of still holds.
        assertThat(RoleFamilyTaxonomy.classify("Sales Associate - Building Materials", null))
                .isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Target Security Specialist", null))
                .isEqualTo("Store security");
        assertThat(RoleFamilyTaxonomy.classify("Senior Project Manager - Data Center Construction", null))
                .as("measured: priority-listing project manager took nine of these from Construction")
                .isEqualTo("Construction");

        RoleMatchClassifier classifier = new RoleMatchClassifier();
        assertThat(classifier.classifyJob("Cashier", "Front End", null).families())
                .doesNotContain(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(classifier.classifyJob("Front End Lead", "Engineering", null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
    }

    /**
     * A picked label is an identity; free text is a guess.
     *
     * <p>These are the substring hits that gutted feeds when one table answered
     * both questions. They are still the answer for ranking, and must never be
     * the answer for hiding -- which is why {@link RoleFamilyTaxonomy#pickedLabel}
     * exists separately from {@link RoleFamilyTaxonomy#classifyTerm}.
     */
    @Test
    @DisplayName("free text never counts as a picked label")
    void freeTextIsNotAPickedLabel() {
        assertThat(RoleFamilyTaxonomy.classifyTerm("Privacy Engineer")).isEqualTo("Legal");
        assertThat(RoleFamilyTaxonomy.pickedLabel("Privacy Engineer")).isNull();

        assertThat(RoleFamilyTaxonomy.classifyTerm("Driving Instructor")).isEqualTo("Teaching");
        assertThat(RoleFamilyTaxonomy.pickedLabel("Driving Instructor")).isNull();

        assertThat(RoleFamilyTaxonomy.classifyTerm("Technical Support Engineer")).isEqualTo("Customer service");
        assertThat(RoleFamilyTaxonomy.pickedLabel("Technical Support Engineer")).isNull();

        // Offered labels, in whatever case they come back in.
        assertThat(RoleFamilyTaxonomy.pickedLabel("Warehouse")).isEqualTo("Warehouse");
        assertThat(RoleFamilyTaxonomy.pickedLabel("store security")).isEqualTo("Store security");
        assertThat(RoleFamilyTaxonomy.pickedLabel("IT Support")).isEqualTo("IT support");
        assertThat(RoleFamilyTaxonomy.pickedLabel(null)).isNull();
    }

    /**
     * A seed has to be a phrase that appears in titles, not a word for the work.
     *
     * <p>Guarding the table rather than the numbers in its comments: a seed
     * naming a family that does not exist is dead, and a seed equal to its own
     * label adds nothing because the label is already searched on its own.
     */
    @Test
    @DisplayName("every retrieval seed belongs to a real family and is not the label again")
    void retrievalSeedsAreWellFormed() {
        List<String> labels = RoleFamilyTaxonomy.labels();
        List<String> withSeeds = labels.stream()
                .filter(label -> !RoleFamilyTaxonomy.retrievalSeeds(label).isEmpty())
                .toList();
        assertThat(withSeeds).as("the seed table cannot be empty").isNotEmpty();

        for (String label : withSeeds) {
            for (String seed : RoleFamilyTaxonomy.retrievalSeeds(label)) {
                assertThat(seed).isNotBlank().isLowerCase();
                assertThat(seed)
                        .as(label + " already searches its own label; " + seed + " adds nothing")
                        .isNotEqualToIgnoringCase(label);
                // A seed that its own family does not classify into itself is
                // retrieving for the wrong bucket.
                assertThat(RoleFamilyTaxonomy.classify(seed, null))
                        .as("seed " + seed + " should read as " + label)
                        .isEqualTo(label);
            }
        }

        assertThat(RoleFamilyTaxonomy.retrievalSeeds("Maintenance"))
                .as("the family whose label retrieved zero real jobs")
                .isNotEmpty();
        assertThat(RoleFamilyTaxonomy.retrievalSeeds(null)).isEmpty();
        assertThat(RoleFamilyTaxonomy.retrievalSeeds("Not A Family")).isEmpty();
    }

    private static List<String> declaredAdjacencyNames() {
        List<String> names = new ArrayList<>();
        for (String label : RoleFamilyTaxonomy.labels()) {
            for (String other : RoleFamilyTaxonomy.labels()) {
                if (RoleFamilyTaxonomy.adjacent(label, other)) {
                    names.add(other);
                }
            }
        }
        assertThat(names).as("the adjacency table cannot be empty").isNotEmpty();
        return names;
    }
}
