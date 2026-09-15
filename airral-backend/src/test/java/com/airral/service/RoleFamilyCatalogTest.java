package com.airral.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role families onboarding offers, held against real posting titles.
 *
 * <p>Onboarding blocked on a hardcoded list of 24 role options, every one of them
 * tech or white-collar. Measured over the 16,520 active postings on 2026-09-15,
 * roughly 70% of titles are neither, and there was no option at all for
 * warehouse, fulfillment, cashier, driver, cook or housekeeping work. So the
 * options are now counted out of the corpus, and what needs guarding is the
 * grouping that turns "Full Time - Fulfillment Associate - Flexible" into a
 * label a person recognises.
 *
 * <p>Every title below is copied from a live posting, with its posting count on
 * 2026-09-15 where that matters. Three properties are worth holding:
 *
 * <p>One, the ordering. Keyword order decides the answer, and the frontline
 * families deliberately come first. {@link RoleMatchClassifier}, which has the
 * reverse order, puts 15.2% of the live corpus into a "Sales" bucket that is
 * almost entirely Lowe's store staff -- the tests below are what stops that
 * ordering creeping back in.
 *
 * <p>Two, that an unplaceable title returns null. The count next to a family
 * label is a claim about how many jobs a candidate would find there, so a title
 * we cannot place has to be reported as unplaced rather than pushed into the
 * nearest bucket. On live data that is 13.6% of postings, and the page shows the
 * figure and keeps a free-text box instead of hiding it.
 *
 * <p>Three, that the keyword table cannot fail silently. A keyword that does not
 * survive its own normalization, or a label carrying an ampersand, matches or
 * breaks nothing visible at compile time and costs a family its jobs in
 * production.
 */
class RoleFamilyCatalogTest {

    @Test
    @DisplayName("frontline titles the hardcoded list had no option for get a family")
    void classifiesFrontlineTitles() {
        // The exact titles, and their live posting counts, that a candidate had
        // no way to ask for before this: 379 fulfillment, 303 merchandising,
        // 401 cashier, 345 stocker.
        assertThat(family("Part Time - Fulfillment Associate - Flexible")).isEqualTo("Warehouse");
        assertThat(family("Warehouse Worker - Food Distribution Center (T3897)")).isEqualTo("Warehouse");
        assertThat(family("Full Time - Receiver/Stocker - Day")).isEqualTo("Warehouse");
        assertThat(family("Seasonal Full Time Hourly Warehouse Operations Openings (T3841)")).isEqualTo("Warehouse");
        assertThat(family("Full Time - Head Cashier - Flexible")).isEqualTo("Retail");
        assertThat(family("Full Time - Merchandising Service Associate - Day")).isEqualTo("Retail");
        assertThat(family("Full Time - CDL Delivery Driver")).isEqualTo("Driver");
        assertThat(family("Customer Delivery Driver")).isEqualTo("Driver");
        assertThat(family("Starbucks Barista")).isEqualTo("Food service");
        assertThat(family("Car Detailer")).isEqualTo("Automotive");
        assertThat(family("Entry-level Auto Technician")).isEqualTo("Automotive");
    }

    @Test
    @DisplayName("a retail floor title is retail, not business development")
    void retailFloorTitlesBeatSales() {
        // These four alone are 307 live postings. "Sales Associate" and "Sales
        // Specialist" read as sales to any keyword list that checks sales first,
        // and they are hourly store jobs in a Lowe's building-materials aisle.
        // Someone who picked "Sales" expecting account work and got these would
        // be right to say the product had misunderstood them.
        assertThat(family("Full Time - Sales Associate - Building Materials - Day")).isEqualTo("Retail");
        assertThat(family("Full Time - Sales Specialist - Millwork - Day")).isEqualTo("Retail");
        assertThat(family("Sales Floor Dept Supervisor - Flooring-Decor")).isEqualTo("Retail");
        assertThat(family("Specialty Sales Team Leader")).isEqualTo("Retail");

        // And the reverse still has to hold, or the fix has simply moved the bug.
        assertThat(family("Strategic Account Executive")).isEqualTo("Sales");
        assertThat(family("Sales Development Representative")).isEqualTo("Sales");
        assertThat(family("Enterprise Account Executive")).isEqualTo("Sales");
    }

    @Test
    @DisplayName("store security is not security engineering")
    void storeSecurityBeatsSecurityEngineering() {
        // 237 live postings of the first one. It is a Target store role, and
        // offering it to someone who asked for security engineering -- or hiding
        // it from someone who wants store work -- are both wrong.
        assertThat(family("Target Security Specialist")).isEqualTo("Store security");
        assertThat(family("Assets Protection Team Leader")).isEqualTo("Store security");
        assertThat(family("Senior Security Engineer, Detection & Response")).isEqualTo("Security engineer");
        assertThat(family("Staff Cybersecurity Engineer")).isEqualTo("Security engineer");
    }

    @Test
    @DisplayName("a keyword does not claim titles that merely contain it")
    void keywordsDoNotMatchInsideLongerWords() {
        // Two keywords were wide enough to be measured wrong on live data, and
        // both failures were invisible: the family still had a plausible count
        // next to it, the count was just describing other people's jobs.
        //
        // "mechanic" unpadded is inside "mechanical", and pulled 86 postings of
        // mechanical engineering into Automotive against only 30 real mechanic
        // jobs. Both directions have to hold, which is why the real ones are
        // asserted here too -- padding a keyword is the easy way to delete a
        // family by accident.
        assertThat(family("Mechanical Engineer")).isNotEqualTo("Automotive");
        assertThat(family("Senior Mechanical Design Engineer")).isNotEqualTo("Automotive");
        assertThat(family("Auto Mechanic - Brake and Tire")).isEqualTo("Automotive");
        assertThat(family("Automotive Mechanic Supervisor")).isEqualTo("Automotive");

        // "inbound"/"outbound" decided 38 live postings and none of them was
        // warehouse work: 13 inbound/outbound product managers, 6 inbound and
        // outbound SDRs, and Target's "Inbound Operations Team Leader", which is
        // the only one of the three the narrowed keyword still claims.
        assertThat(family("Inbound Operations Team Leader")).isEqualTo("Warehouse");
        assertThat(family("Senior Staff Inbound Product Manager")).isEqualTo("Product manager");
        assertThat(family("Outbound Sales Development Representative")).isEqualTo("Sales");
        // Target's truck-unload titles do not depend on the keyword at all.
        assertThat(family("Seasonal: 4am Inbound (Stocking) (T1812)")).isEqualTo("Warehouse");
    }

    @Test
    @DisplayName("a title we cannot place returns no family rather than the nearest one")
    void unplaceableTitlesReturnNull() {
        // Real unplaced titles. The tail is long and shallow -- its biggest
        // single title is 11 postings -- so the honest answer is that we do not
        // know, which is what sends the candidate to the free-text box.
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Closing Team Leader", null)).isNull();
        assertThat(ExternalJobPostingStore.classifyRoleFamily("MST ASM", null)).isNull();
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Associate, Member and Provider Optimization", null)).isNull();
        assertThat(ExternalJobPostingStore.classifyRoleFamily(null, null)).isNull();
        assertThat(ExternalJobPostingStore.classifyRoleFamily("   ", "  ")).isNull();
    }

    @Test
    @DisplayName("department only decides when the title says nothing")
    void departmentIsOnlyAFallback() {
        // Worth 8 points of coverage on live data (78.3% to 86.4%) and mostly
        // right: "Shift Lead" under Order Fulfillment is warehouse work.
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Shift Lead", "112 Order Fulfillment"))
                .isEqualTo("Warehouse");
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Regional Director, Enterprise", "Sales Department"))
                .isEqualTo("Sales");

        // But it is the weaker signal, so a title that speaks for itself wins.
        // Without this, a software engineer in a department called Operations is
        // filed as operations.
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Senior Software Engineer", "Business Operations"))
                .isEqualTo("Software engineer");
        assertThat(ExternalJobPostingStore.classifyRoleFamily("Staff Product Designer", "Sales"))
                .isEqualTo("Design");
    }

    @Test
    @DisplayName("every keyword survives the normalization it is matched against")
    void keywordsSurviveNormalization() {
        // Matching is substring containment against a title that has been
        // lowercased and had its punctuation flattened to single spaces. A
        // keyword written in any other shape -- an uppercase letter, a slash, a
        // double space -- simply never matches, and nothing anywhere reports it.
        // The family quietly loses its jobs and the option disappears from
        // onboarding, which is the failure this whole change exists to fix.
        List<String> unmatchable = new ArrayList<>();
        for (String keyword : allKeywords()) {
            String normalized = keyword.toLowerCase(Locale.US)
                    .replace("&", " and ")
                    .replaceAll("[^a-z0-9]+", " ")
                    .replaceAll("\\s+", " ");
            if (!normalized.equals(keyword)) {
                unmatchable.add(keyword);
            }
        }
        assertThat(unmatchable).isEmpty();
    }

    @Test
    @DisplayName("labels are unique and usable as the search term they become")
    void labelsAreUniqueAndSearchable() {
        List<String> labels = allLabels();
        assertThat(labels).doesNotHaveDuplicates();

        // The label is also the term the first job feed is seeded with, and that
        // seed runs through plainto_tsquery, which ANDs its terms. Measured on
        // the live API: "Warehouse & Fulfillment" returns 161 jobs where
        // "Warehouse" alone returns 513 and "Fulfillment" alone 1,233. A compound
        // label does not just read badly, it empties the first feed a new
        // candidate ever sees.
        assertThat(labels).allSatisfy(label -> {
            assertThat(label).doesNotContain("&").doesNotContain("/");
            assertThat(label.split(" ")).hasSizeLessThanOrEqualTo(2);
        });
    }

    private static String family(String title) {
        return ExternalJobPostingStore.classifyRoleFamily(title, null);
    }

    private static List<String> allKeywords() {
        List<String> keywords = new ArrayList<>();
        for (RoleFamilyTaxonomy.RoleFamilyRule rule : RoleFamilyTaxonomy.ROLE_FAMILY_RULES) {
            keywords.addAll(rule.keywords());
        }
        assertThat(keywords).isNotEmpty();
        return keywords;
    }

    private static List<String> allLabels() {
        List<String> labels = RoleFamilyTaxonomy.labels();
        assertThat(labels).isNotEmpty();
        return labels;
    }
}
