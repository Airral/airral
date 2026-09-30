package com.airral.service;

import com.airral.domain.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of what a job asks for an application shows.
 */
class JobAlignmentTest {

    private static Job withKeywords(String... keywords) {
        return Job.builder().title("Role").atsKeywords(keywords).build();
    }

    @Test
    @DisplayName("the job's keywords are found as whole words, and a known skill under any of its names")
    void keywordsInTheResume() {
        JobAlignment.Result result = JobAlignment.of(withKeywords("Java", "Kubernetes", "Customer service"),
                "Built Java services on k8s for five years.");

        assertThat(result.matched()).containsExactly("Java", "Kubernetes");
        assertThat(result.missing()).containsExactly("Customer service");
        assertThat(result.score()).isEqualTo(66);
    }

    @Test
    @DisplayName("Java is not found in JavaScript, nor a phrase in part of it")
    void noPartialWords() {
        assertThat(JobAlignment.mentions("Five years of JavaScript and TypeScript.", "Java")).isFalse();
        assertThat(JobAlignment.mentions("Ran store operations for a 40-person team.", "store operations")).isTrue();
        assertThat(JobAlignment.mentions("Ran the store.", "store operations")).isFalse();
    }

    @Test
    @DisplayName("a job without keywords is read for the skills its description and requirements name")
    void skillsFromTheJob() {
        Job job = Job.builder().title("Analyst").description("You will own forecasting and budgeting in Excel.")
                .requirements("Python a plus. SQL required.").build();

        JobAlignment.Result result = JobAlignment.of(job, "Excel power user; wrote SQL reports daily; led budgeting.");

        assertThat(JobAlignment.keywordsFor(job))
                .containsExactlyInAnyOrder("Python", "SQL", "Excel", "Forecasting", "Budgeting");
        assertThat(result.matched()).containsExactlyInAnyOrder("SQL", "Excel", "Budgeting");
        assertThat(result.missing()).containsExactlyInAnyOrder("Python", "Forecasting");
    }

    @Test
    @DisplayName("a job that names nothing to look for gets no evidence, and the neutral score")
    void nothingToLookFor() {
        JobAlignment.Result result = JobAlignment.of(Job.builder().title("Helper").description("Help out.").build(),
                "Anything at all.");

        assertThat(result.matched()).isEmpty();
        assertThat(result.missing()).isEmpty();
        assertThat(result.score()).isEqualTo(JobAlignment.NO_KEYWORDS_SCORE);
    }

    @Test
    @DisplayName("repeated keywords count once, however they were typed")
    void keywordsCountOnce() {
        assertThat(JobAlignment.keywordsFor(withKeywords("Excel", " excel ", "", "EXCEL", "Payroll")))
                .containsExactly("Excel", "Payroll");
    }

    @Test
    @DisplayName("a known skill whose catalog names leave its own name out is still found by that name")
    void skillsByTheirOwnName() {
        String resume = "Led operations for a 40-person team and owned people development: leadership by example. "
                + "Built REST APIs and Vue front ends to WCAG 2.1. Oracle and Postgres. Wrote Go services; "
                + "Epic certified.";

        for (String keyword : new String[] {"Leadership", "Operations", "REST APIs", "Vue", "Accessibility",
                "Oracle", "Go", "golang", "Epic"}) {
            assertThat(JobAlignment.mentions(resume, keyword)).as(keyword).isTrue();
        }
    }

    @Test
    @DisplayName("a skill named like an everyday word is not found in the everyday word")
    void everydayWordsAreNotSkills() {
        String resume = "Ready to go-live on day one; planned each epic with the team.";

        assertThat(JobAlignment.mentions(resume, "Go")).isFalse();
        assertThat(JobAlignment.mentions(resume, "Epic")).isFalse();
    }
}
