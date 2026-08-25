package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeJobFitAnalyzerTest {

    private final ResumeJobFitAnalyzer analyzer = new ResumeJobFitAnalyzer(new ObjectMapper());

    @Test
    void separatesRequiredAndPreferredSkillsAndReportsExperienceGap() {
        CandidateResumeDocument resume = CandidateResumeDocument.builder()
                .extractedText("""
                        Software Engineer
                        Skills: Java, AWS
                        Responsible for Java services used by internal teams.
                        """)
                .parsedSkills(Json.of("[\"Java\",\"AWS\"]"))
                .parsedExperience(Json.of("[{\"title\":\"Software Engineer\"}]"))
                .parsedProfile(Json.of("""
                        {"headline":"Software Engineer","recentTitles":["Software Engineer"],
                         "experienceYears":3.0,"parseConfidenceScore":95}
                        """))
                .build();
        CandidateJobDetailResponse job = CandidateJobDetailResponse.builder()
                .title("Backend Software Engineer")
                .department("Engineering")
                .descriptionText("""
                        Minimum qualifications: 5+ years experience.
                        Required: Java and Kubernetes.
                        Preferred: AWS and Terraform.
                        """)
                .tags(List.of("Java", "Kubernetes", "AWS"))
                .build();

        ResumeJobFitAnalyzer.FitAnalysis result = analyzer.analyze(resume, job);

        assertThat(result.matchedRequirements()).contains("Java", "AWS");
        assertThat(result.missingRequirements())
                .contains("Required: Kubernetes", "Preferred: Terraform")
                .anyMatch(value -> value.startsWith("Experience: 5+ years requested"));
        assertThat(result.keywordGaps()).contains("Kubernetes").doesNotContain("Terraform");
        assertThat(result.fitScore()).isBetween(40, 79);
        assertThat(result.weakBullets()).singleElement().asString().contains("action you owned");
        assertThat(result.suggestedRewrites()).anyMatch(value -> value.startsWith("Only if accurate"));
    }

    @Test
    void doesNotTreatPreferredGapAsCoreKeywordGap() {
        CandidateResumeDocument resume = CandidateResumeDocument.builder()
                .extractedText("Senior financial analyst using Excel and SAP for forecasting and budgeting.")
                .parsedSkills(Json.of("[\"Financial Analysis\",\"Excel\",\"SAP\",\"Forecasting\",\"Budgeting\"]"))
                .parsedExperience(Json.of("[{\"title\":\"Senior Financial Analyst\"}]"))
                .parsedProfile(Json.of("{\"headline\":\"Senior Financial Analyst\",\"experienceYears\":6,\"parseConfidenceScore\":90}"))
                .build();
        CandidateJobDetailResponse job = CandidateJobDetailResponse.builder()
                .title("Senior Financial Analyst")
                .department("Finance")
                .descriptionText("Required: financial analysis, Excel, forecasting, and budgeting. Preferred: Power BI.")
                .build();

        ResumeJobFitAnalyzer.FitAnalysis result = analyzer.analyze(resume, job);

        assertThat(result.missingRequirements()).contains("Preferred: Power BI");
        assertThat(result.keywordGaps()).isEmpty();
        assertThat(result.fitScore()).isGreaterThanOrEqualTo(80);
    }
}
