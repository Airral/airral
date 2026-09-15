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

    @Test
    @DisplayName("adjacency is symmetric and names only real families")
    void adjacencyIsWellFormed() {
        List<String> labels = RoleFamilyTaxonomy.labels();

        // A typo in the adjacency table is silent: the entry simply never
        // matches any job family, and the near-miss score is never awarded.
        for (String family : labels) {
            for (String other : labels) {
                if (RoleFamilyTaxonomy.adjacent(family, other)) {
                    assertThat(RoleFamilyTaxonomy.adjacent(other, family))
                            .as(family + " is near " + other + ", so the reverse must hold too")
                            .isTrue();
                }
            }
        }

        // Anything declared that is not in the label list matches nothing, so
        // sweep the declared names against the families that actually exist.
        assertThat(declaredAdjacencyNames())
                .as("an adjacency entry naming a family that does not exist is dead")
                .allMatch(labels::contains);
    }

    @Test
    @DisplayName("a family is never its own neighbour")
    void familiesAreNotAdjacentToThemselves() {
        // Same-family and near-family score differently and print different
        // sentences, so an entry pairing a family with itself would make the
        // weaker of the two reachable for an exact match.
        for (String label : RoleFamilyTaxonomy.labels()) {
            assertThat(RoleFamilyTaxonomy.adjacent(label, label))
                    .as(label + " should be an exact match, not a near one")
                    .isFalse();
        }
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

        RoleMatchClassifier classifier = new RoleMatchClassifier();
        assertThat(classifier.classifyJob("Cashier", "Front End", null).families())
                .doesNotContain(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(classifier.classifyJob("Frontend Engineer", "Engineering", null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(classifier.classifyJob("Front End Developer", "Engineering", null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
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
