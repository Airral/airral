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
     * A family has to be able to recognise its own name in a posting title.
     *
     * <p>Seven labels could not, and fixing only the candidate side left the
     * halves disagreeing: a candidate who picked "Maintenance" got a family
     * while "Maintenance Worker" and a "Maintenance" department placed nowhere,
     * so the postings that matched them sat at the score floor and the feed
     * filled with unplaceable white-collar titles instead.
     */
    @Test
    @DisplayName("a posting titled with the bare family name places in that family")
    void bareFamilyNameInATitlePlaces() {
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Worker", null)).isEqualTo("Maintenance");
        assertThat(RoleFamilyTaxonomy.classify("Shift Lead", "Maintenance")).isEqualTo("Maintenance");
        // Healthcare is the deliberate exception -- see its rule for the corpus
        // measurement. In this catalogue the bare word modifies another domain
        // more often than it names the work, so it is read by the clinical
        // entries instead and a title carrying only the word places nowhere.
        assertThat(RoleFamilyTaxonomy.classify("Senior Project Manager - Healthcare Construction", "Real estate"))
                .isNotEqualTo("Healthcare");
        assertThat(RoleFamilyTaxonomy.classify("Nurse Practitioner", null)).isEqualTo("Healthcare");
        assertThat(RoleFamilyTaxonomy.classify("Administrative Coordinator", null)).isEqualTo("Administrative");
        assertThat(RoleFamilyTaxonomy.classify("Teaching Assistant", null)).isEqualTo("Teaching");
        assertThat(RoleFamilyTaxonomy.classify("Data Science Manager", null)).isEqualTo("Data science");

        // The bare label goes last in its family, so the specific entries above
        // it still decide. A nurse is Healthcare by "nurse", not by the label.
        assertThat(RoleFamilyTaxonomy.classify("Registered Nurse", null)).isEqualTo("Healthcare");
        assertThat(RoleFamilyTaxonomy.classify("Maintenance Technician", null)).isEqualTo("Maintenance");
    }

    /**
     * Both readings of "front end" have to work.
     *
     * <p>Dropping Retail's "front end" to stop it claiming software titles cost
     * four real grocery titles and every posting whose only signal was a Front
     * End department. The previous test here pinned {@code classify("Cashier",
     * "Front End")} and passed for the wrong reason -- the title "Cashier"
     * matches Retail on its own, so the department was never consulted and the
     * case the test documented was never covered.
     */
    @Test
    @DisplayName("front end is the store, unless the title says engineer")
    void frontEndReadsBothWays() {
        // Retail titles and the bare department, with no other retail signal.
        assertThat(RoleFamilyTaxonomy.classify("Front End Associate", null)).isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Front End Supervisor", null)).isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Front End Clerk", null)).isEqualTo("Retail");
        assertThat(RoleFamilyTaxonomy.classify("Closing Team Leader", "Front End")).isEqualTo("Retail");

        // Software titles that share the prefix.
        assertThat(RoleFamilyTaxonomy.classify("Front End Engineer", "Engineering"))
                .isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Front End Developer", null)).isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Front End Lead", "Engineering")).isEqualTo("Software engineer");
        assertThat(RoleFamilyTaxonomy.classify("Back End Engineer", null)).isEqualTo("Software engineer");

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
