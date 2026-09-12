package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quantified-achievement check, which could not see the two characters that
 * mark a quantified achievement.
 *
 * <p>It is worth 20 of the 100 health points and it is the one category that
 * asks whether the resume says what the candidate actually did. The regex wrapped
 * {@code \b} around alternatives ending in {@code %} and beginning with {@code $},
 * and a word boundary is impossible in both places: "%" and the space after it are
 * both non-word, "$" and the space before it are both non-word. So "increased
 * revenue 40%" and "raised $2M" scored nothing while "20 projects" scored, because
 * "s" is a word character. Percentages and dollar figures -- the overwhelming
 * majority of real metrics -- were invisible to the check built to find them, and
 * a 677-word document of nonsense came back 100/100, grade A.
 *
 * <p>These tests hold the fix in both directions, because only one of them is the
 * bug and both of them are lies. Above: figures a recruiter would call a metric
 * are counted. Below: numbers that measure nothing -- a phone number, a date
 * range, a years-of-experience line, a version, a credential id -- are still not
 * counted, and prose with no figures in it still scores zero. Widening the pattern
 * until every number counts would earn back the same 20 points for the same
 * nonsense document, which is the failure this exists to remove, pointed the other
 * way.
 *
 * <p>Asserted through the public analyze(), on the points the category actually
 * awards: one metric is 5 points, two are 10, six or more are the full 20.
 */
class ResumeQuantifiedMetricsTest {

    private final ResumeHealthScoreService service = new ResumeHealthScoreService(new ObjectMapper());

    /** Points the quantified-achievements category awards this text, out of 20. */
    private int points(String resumeText) {
        return analyze(resumeText).categories().get("achievements").score();
    }

    private List<String> issueCodes(String resumeText) {
        return analyze(resumeText).issues().stream()
                .map(ResumeHealthScoreService.ResumeIssue::code)
                .toList();
    }

    private ResumeHealthScoreService.ResumeHealthResult analyze(String resumeText) {
        return service.analyze(CandidateResumeDocument.builder()
                .extractedText(resumeText)
                .build());
    }

    // ------------------------------------------------------------ real metrics

    @Test
    @DisplayName("a percentage is a metric, whatever follows the percent sign")
    void percentagesAreDetected() {
        // Each of these scored 0 before: the trailing \b could never hold after "%".
        assertThat(points("Increased revenue 40% year over year")).isEqualTo(5);
        assertThat(points("Reduced infrastructure spend 12.5%")).isEqualTo(5);
        assertThat(points("Drove a 15 % increase in trial-to-paid conversion")).isEqualTo(5);
        assertThat(points("Reduced onboarding time 40 percent")).isEqualTo(5);
    }

    @Test
    @DisplayName("a dollar figure is a metric, with or without a K/M/B suffix")
    void dollarFiguresAreDetected() {
        // Each of these scored 0 before: the leading \b could never hold before "$".
        assertThat(points("Raised $2M in seed funding")).isEqualTo(5);
        assertThat(points("Closed $450K in new business")).isEqualTo(5);
        assertThat(points("Saved $3,500 per month in vendor costs")).isEqualTo(5);
        assertThat(points("Grew ARR from $1.2M to $4.8M")).isEqualTo(10);
    }

    @Test
    @DisplayName("counts and multipliers are metrics too")
    void countsAndMultipliersAreDetected() {
        assertThat(points("Delivered 20 projects across 3 teams")).isEqualTo(10);
        assertThat(points("Supported 1,200+ users on a 24x7 rotation")).isEqualTo(5);
        assertThat(points("Scaled throughput 3x after the rewrite")).isEqualTo(5);
    }

    @Test
    @DisplayName("an experience section full of metrics earns the whole category")
    void aMetricRichSectionEarnsFullPoints() {
        String experience = """
                Increased checkout conversion 40% while cutting p95 latency 38%.
                Grew ARR from $1.2M to $4.8M and closed $450K in new business.
                Delivered 20 projects across 3 teams.
                """;

        assertThat(points(experience)).isEqualTo(20);
        assertThat(issueCodes(experience)).doesNotContain("NO_METRICS", "FEW_METRICS");
    }

    // ------------------------------------------------- numbers that measure nothing

    @Test
    @DisplayName("prose with no figures in it still scores nothing and still says so")
    void proseWithoutMetricsIsStillUnquantified() {
        String prose = "Responsible for the customer onboarding process. Worked on internal tooling "
                + "and assisted in migration planning. Participated in weekly reviews with the design team.";

        assertThat(points(prose)).isZero();
        assertThat(issueCodes(prose)).contains("NO_METRICS");
    }

    @Test
    @DisplayName("a number that measures nothing is not an achievement")
    void numbersWithoutAMeasurementAreNotCounted() {
        // Every branch of the pattern requires a unit or a counted noun, which is
        // what keeps these out. Counting any of them would be its own overstatement:
        // a phone number is not an outcome, and neither is a graduation year.
        assertThat(points("Contact: jane.doe@example.com | (555) 123-4567 | linkedin.com/in/janedoe")).isZero();
        assertThat(points("Senior Engineer, Acme Corp, January 2019 to March 2023")).isZero();
        assertThat(points("Bachelor of Science, Computer Science, 2018")).isZero();
        assertThat(points("Certified Kubernetes Administrator, credential id 1234567890")).isZero();
        assertThat(points("Maintained a 24x7 on-call rotation")).isZero();
    }

    @Test
    @DisplayName("time served is not an achievement, however it is written")
    void yearsOfExperienceIsNotAMetric() {
        // The tempting widening, and the wrong one. A years-of-experience line is on
        // almost every resume, so counting it would hand the category to every
        // document that has a career history, which is exactly the failure that made
        // a nonsense document score 100.
        assertThat(points("Over 10 years of experience building distributed systems")).isZero();
        assertThat(points("5+ years of experience with Java 8+ and Spring Boot")).isZero();
    }
}
