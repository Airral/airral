package com.airral.service;

import com.airral.domain.CandidateProfile;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CandidateJobSearchServiceTest {

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
            "airbnb",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "US",
            45,
            0,
            1
    );

    @Test
    void infersTargetRoleAndSeniorityFromParsedResumeProfile() {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", parsedResumeProfile());
        List<CandidateJobSummaryResponse> ranked = ReflectionTestUtils.invokeMethod(
                service,
                "rankPersonalizedJobs",
                List.of(
                        softwareJob("GREENHOUSE:one:1", "Backend Software Engineer", "Java Spring Boot React"),
                        softwareJob("GREENHOUSE:two:2", "Director of Sales", "Sales enterprise accounts"),
                        softwareJob("GREENHOUSE:three:3", "Expert Compliance Risk Advisor (Remote)", "Compliance Risk Advisor Full-time"),
                        softwareJob("GREENHOUSE:four:4", "Senior Account Executive, Agency Sales", "Sales Account Executive"),
                        softwareJob("GREENHOUSE:five:5", "Jr People Data Analyst", "People Data Analyst")
                ),
                context
        );

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getTitle()).isEqualTo("Backend Software Engineer");
        assertThat(ranked.get(0).getMatchScore()).isGreaterThanOrEqualTo(80);
        assertThat(ranked.get(0).getMatchReasons()).anyMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(ranked.get(0).getMatchReasons()).anyMatch(reason -> reason.startsWith("Skill match:"));

        List<String> retrievalQueries = ReflectionTestUtils.invokeMethod(service, "retrievalQueriesFor", context, null);
        assertThat(retrievalQueries).contains("software engineer");
    }

    @Test
    void fullStackTargetDoesNotMatchFullTimeComplianceJob() {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", parsedFullStackProfile());
        List<CandidateJobSummaryResponse> ranked = ReflectionTestUtils.invokeMethod(
                service,
                "rankPersonalizedJobs",
                List.of(
                        softwareJob("GREENHOUSE:one:1", "Software Engineer, Full Stack", "Java React TypeScript"),
                        softwareJob("GREENHOUSE:two:2", "Expert Compliance Risk Advisor (Remote)", "Compliance Risk Advisor Full-time"),
                        softwareJob("GREENHOUSE:three:3", "Account Executive, Small City", "Sales Account Executive Full-time")
                ),
                context
        );

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).getTitle()).isEqualTo("Software Engineer, Full Stack");
        assertThat(ranked.get(0).getMatchReasons()).anyMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(ranked.get(0).getMatchReasons()).noneMatch(reason -> reason.contains("outside your target"));
    }

    @Test
    void softwareIcProfileDoesNotRankPeopleManagementOrStaffRoles() {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", parsedResumeProfile());
        List<CandidateJobSummaryResponse> ranked = ReflectionTestUtils.invokeMethod(
                service,
                "rankPersonalizedJobs",
                List.of(
                        softwareJob("GREENHOUSE:coinbase:1", "Senior Software Engineer - Blockchain Network", "Java Spring Boot Distributed Systems"),
                        softwareJob("GREENHOUSE:gitlab:2", "Engineering Manager, Data Foundations", "Engineering Manager Data"),
                        softwareJob("GREENHOUSE:gitlab:3", "Engineering Manager, Growth", "Engineering Manager Growth"),
                        softwareJob("GREENHOUSE:experian:4", "Senior Manager, Thought Leadership and Insights", "Senior Manager Strategy"),
                        softwareJob("GREENHOUSE:mongodb:5", "Site Reliability Engineer (Senior or Staff)", "SRE Kubernetes Observability")
                ),
                context
        );

        assertThat(ranked).extracting(CandidateJobSummaryResponse::getTitle)
                .containsExactly("Senior Software Engineer - Blockchain Network");
        assertThat(ranked.get(0).getMatchScore()).isGreaterThanOrEqualTo(75);
    }

    @Test
    void roleClassifierToleratesIncompleteJobRows() {
        RoleMatchClassifier classifier = new RoleMatchClassifier();

        assertThat(classifier.classifyJob(null, null, null).isKnown()).isFalse();
        assertThat(classifier.classifyJob("Backend Software Engineer", null, null).families())
                .contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
    }

    @Test
    void roleClassifierSeparatesSoftwareFamilyFromPeopleManagementTrack() {
        RoleMatchClassifier classifier = new RoleMatchClassifier();
        RoleMatchClassifier.RoleIntent softwareProfile = classifier.classifyProfile(
                Set.of("Software Engineer"),
                "Software Engineer II",
                Set.of("Java", "Spring Boot"));
        RoleMatchClassifier.RoleIntent engineeringManager = classifier.classifyJob(
                "Engineering Manager, Data Foundations",
                "Engineering",
                List.of("Data Foundations"));

        assertThat(engineeringManager.families()).contains(RoleMatchClassifier.RoleFamily.SOFTWARE_ENGINEERING);
        assertThat(engineeringManager.track()).isEqualTo(RoleMatchClassifier.RoleTrack.PEOPLE_MANAGEMENT);
        assertThat(classifier.compatible(softwareProfile, engineeringManager)).isTrue();
        assertThat(classifier.careerTrackCompatible(softwareProfile, engineeringManager)).isFalse();
    }

    @Test
    void roleClassifierInfersOperationsFamilyFromResumeSkills() {
        RoleMatchClassifier classifier = new RoleMatchClassifier();
        RoleMatchClassifier.RoleIntent profile = classifier.classifyProfile(
                Set.of(),
                null,
                Set.of("Procurement", "Supply Chain", "Inventory Management"));
        RoleMatchClassifier.RoleIntent job = classifier.classifyJob(
                "Supply Chain Specialist",
                "Operations",
                List.of("Procurement"));

        assertThat(profile.families()).contains(RoleMatchClassifier.RoleFamily.OPERATIONS);
        assertThat(classifier.compatible(profile, job)).isTrue();
    }

    @Test
    void personalizesSelectedJobDetailWithDescriptionText() {
        Object context = ReflectionTestUtils.invokeMethod(service, "toCandidateMatchContext", parsedResumeProfile());
        CandidateJobDetailResponse detail = CandidateJobDetailResponse.builder()
                .jobId("GREENHOUSE:affirm:123")
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken("affirm")
                .externalJobId("123")
                .title("Software Engineer, Backend")
                .companyName("Affirm")
                .department("Engineering")
                .location("Remote US")
                .workMode("REMOTE")
                .employmentType("Full-time")
                .salaryLabel("$160k - $220k")
                .descriptionText("Build Java and Spring Boot microservices on AWS with React dashboards, CI/CD, monitoring, and observability.")
                .sourceUpdatedAt(OffsetDateTime.now())
                .jobQualityScore(92)
                .qualityReasons(List.of("Fresh source date", "Direct apply link"))
                .tags(List.of("Java", "Spring Boot", "AWS"))
                .matchScore(64)
                .build();

        CandidateJobDetailResponse personalized = ReflectionTestUtils.invokeMethod(service, "applyCandidateMatch", detail, context);

        assertThat(personalized.getMatchScore()).isGreaterThan(64);
        assertThat(personalized.getMatchReasons()).anyMatch(reason -> reason.startsWith("Role fit:"));
        assertThat(personalized.getMatchReasons()).anyMatch(reason -> reason.startsWith("Skill match:"));
    }

    @Test
    void appliesExperienceAndVisaFiltersBeforeJobsArePaged() {
        CandidateJobSummaryResponse sponsoredSenior = filterJob(
                "Sponsored Senior", "REMOTE", "$150k - $190k", "SPONSORS", "Senior", 5);
        CandidateJobSummaryResponse unknownSenior = filterJob(
                "Unknown Senior", "REMOTE", "$140k - $180k", "UNKNOWN", "Senior", 6);
        CandidateJobSummaryResponse noSponsorSenior = filterJob(
                "No Sponsor Senior", "REMOTE", "$145k - $185k", "NO_SPONSORSHIP", "Senior", 5);
        CandidateJobSummaryResponse sponsoredEntry = filterJob(
                "Sponsored Entry", "REMOTE", "$90k - $120k", "SPONSORS", "Entry", 1);

        List<CandidateJobSummaryResponse> filtered = ReflectionTestUtils.invokeMethod(
                service,
                "applyExplicitFilters",
                List.of(sponsoredSenior, unknownSenior, noSponsorSenior, sponsoredEntry),
                "REMOTE",
                true,
                "senior",
                true
        );

        assertThat(filtered)
                .extracting(CandidateJobSummaryResponse::getTitle)
                .containsExactly("Sponsored Senior", "Unknown Senior");
    }

    @Test
    void keepsJobsWithUnknownExperienceWhenFilteringByLevel() {
        CandidateJobSummaryResponse unknownExperience = filterJob(
                "Backend Engineer", "HYBRID", null, null, null, null);

        List<CandidateJobSummaryResponse> filtered = ReflectionTestUtils.invokeMethod(
                service,
                "applyExplicitFilters",
                List.of(unknownExperience),
                null,
                false,
                "mid",
                false
        );

        assertThat(filtered).containsExactly(unknownExperience);
    }

    private CandidateProfile parsedResumeProfile() {
        return CandidateProfile.builder()
                .headline("Software Engineer II at The Home Depot")
                .location("Boston, MA")
                .preferredWorkMode("REMOTE")
                .skills(Json.of("[\"Java\",\"Spring Boot\",\"React\",\"AWS\",\"Docker\",\"Kubernetes\",\"GitHub Actions\",\"Monitoring\",\"Observability\"]"))
                .experience(Json.of("[{\"company\":\"The Home Depot\",\"title\":\"Software Engineer II\",\"startDate\":\"June 2024\",\"endDate\":\"Present\",\"current\":true},{\"company\":\"The Home Depot\",\"title\":\"Software Engineer I\",\"startDate\":\"May 2022\",\"endDate\":\"June 2024\",\"current\":false}]"))
                .matchPreferences(Json.of("{}"))
                .build();
    }

    private CandidateProfile parsedFullStackProfile() {
        return CandidateProfile.builder()
                .headline("Software Engineer II at The Home Depot")
                .location("Boston, MA")
                .preferredWorkMode("REMOTE")
                .skills(Json.of("[\"Java\",\"Spring Boot\",\"React\",\"TypeScript\",\"AWS\",\"Docker\"]"))
                .experience(Json.of("[{\"company\":\"The Home Depot\",\"title\":\"Software Engineer II\",\"startDate\":\"June 2024\",\"endDate\":\"Present\",\"current\":true},{\"company\":\"The Home Depot\",\"title\":\"Software Engineer I\",\"startDate\":\"May 2022\",\"endDate\":\"June 2024\",\"current\":false}]"))
                .matchPreferences(Json.of("{\"targetRoles\":[\"Full Stack Engineer\"]}"))
                .build();
    }

    private CandidateJobSummaryResponse softwareJob(String jobId, String title, String tags) {
        return CandidateJobSummaryResponse.builder()
                .jobId(jobId)
                .sourceType("GREENHOUSE")
                .sourceName("Greenhouse")
                .sourceBoardToken("test")
                .externalJobId(jobId.substring(jobId.lastIndexOf(':') + 1))
                .title(title)
                .companyName("TestCo")
                .department("Engineering")
                .location("Remote US")
                .workMode("REMOTE")
                .employmentType("Full-time")
                .salaryLabel("$150k - $210k")
                .sourceUpdatedAt(OffsetDateTime.now())
                .jobQualityScore(92)
                .qualityReasons(List.of("Fresh source date", "Direct apply link"))
                .tags(List.of(tags.split(" ")))
                .build();
    }

    private CandidateJobSummaryResponse filterJob(
            String title,
            String workMode,
            String salaryLabel,
            String sponsorshipLanguage,
            String seniorityLabel,
            Integer experienceYears) {
        return CandidateJobSummaryResponse.builder()
                .jobId("GREENHOUSE:test:" + title.replace(' ', '-').toLowerCase())
                .title(title)
                .workMode(workMode)
                .salaryLabel(salaryLabel)
                .sponsorshipLanguage(sponsorshipLanguage)
                .seniorityLabel(seniorityLabel)
                .experienceYears(experienceYears)
                .build();
    }
}
