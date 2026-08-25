package com.airral.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeParsingServiceTest {

    private final ResumeParsingService service = new ResumeParsingService();

    @Test
    void extractsProfileSectionsFromStandardResume() throws Exception {
        ByteArrayResource resume = docxResource(List.of(
                "Harjit Singh",
                "(781)-856-4740 | harjit90001@gmail.com | GitHub | LinkedIn",
                "Summary",
                "Software Engineer with experience building scalable distributed systems, microservices, RESTful APIs, and cloud-native applications using Java, Spring Boot, TypeScript, React, MongoDB, Kubernetes, and CI/CD pipelines.",
                "Core Skills",
                "Languages: Java, JavaScript, TypeScript, Python, C/C++",
                "Frameworks & Frontend: Spring Boot, React, Angular, Tailwind CSS",
                "Cloud & DevOps: GCP, AWS, Docker, Kubernetes, GitHub Actions, Jenkins, CI/CD",
                "Databases: MongoDB, PostgreSQL, MySQL, SQL",
                "AI & Automation: LLM Workflows, Static Analysis Tooling, Developer Productivity Automation",
                "Tools: Git, Linux, Jira, Big Query, Vertex AI",
                "Professional Experience",
                "Software Engineer II | The Home Depot | Remote June 2024 - Present",
                "Developed and maintained scalable cloud-native microservices supporting 10M+ monthly transactions.",
                "Built and optimized CI/CD deployment pipelines using GitHub Actions, reducing release time by 80%.",
                "Software Engineer I | The Home Depot | Remote May 2022 - June 2024",
                "Developed a Java-based static analysis tool using JavaParser.",
                "Education",
                "Bachelor of Science in Computer Science: University of Massachusetts, Boston",
                "Awards",
                "BIT Award - Best in Tech, The Home Depot."
        ));

        ResumeParsingService.ParsedResume parsed = service.parse(resume, ".docx").block(Duration.ofSeconds(5));

        assertThat(parsed).isNotNull();
        assertThat(parsed.extractedText()).contains("Harjit Singh", "Professional Experience", "Education");
        assertThat(parsed.skills()).contains(
                "Java",
                "Spring Boot",
                "React",
                "Tailwind CSS",
                "GCP",
                "AWS",
                "Docker",
                "Kubernetes",
                "GitHub Actions",
                "Jenkins",
                "BigQuery",
                "Vertex AI"
        );
        assertThat(parsed.experience()).hasSize(2);
        assertThat(parsed.experience().get(0))
                .containsEntry("title", "Software Engineer II")
                .containsEntry("company", "The Home Depot")
                .containsEntry("startDate", "June 2024")
                .containsEntry("endDate", "Present")
                .containsEntry("current", true);
        assertThat(parsed.experience().get(0).get("description").toString())
                .contains("10M+ monthly transactions", "reducing release time by 80%");

        assertThat(parsed.education()).hasSize(1);
        assertThat(parsed.education().get(0))
                .containsEntry("school", "University of Massachusetts, Boston")
                .containsEntry("degree", "Bachelor of Science in Computer Science")
                .containsEntry("field", "Computer Science");

        assertThat(parsed.parsedProfile())
                .containsEntry("name", "Harjit Singh")
                .containsEntry("email", "harjit90001@gmail.com")
                .containsEntry("headline", "Software Engineer II at The Home Depot");
        assertThat(parsed.parsedProfile().get("summary").toString())
                .contains("Software Engineer with experience building scalable distributed systems");
        assertThat(parsed.parsedProfile().get("detectedSections").toString())
                .contains("summary", "skills", "experience", "education", "awards");
        assertThat(parsed.parsedProfile())
                .containsEntry("parserVersion", "resume-parser-v3")
                .containsKey("experienceYears")
                .containsKey("skillEvidence")
                .containsKey("parseConfidenceScore");
        assertThat((Number) parsed.parsedProfile().get("parseConfidenceScore"))
                .extracting(Number::intValue)
                .isEqualTo(100);
    }

    @Test
    void parsesMultilineExperienceAndCrossIndustrySkills() throws Exception {
        ByteArrayResource resume = docxResource(List.of(
                "Jamie Rivera",
                "jamie@example.com | (617) 555-0100",
                "Professional Summary",
                "Operations analyst improving procurement, inventory, and financial reporting.",
                "Skills",
                "Business: Procurement, Supply Chain, Inventory Management, Excel, Power BI, SAP",
                "Experience",
                "Operations Analyst",
                "Acme Manufacturing | Boston, MA",
                "January 2021 - Present",
                "Reduced inventory costs by 18% using Power BI and SAP reporting.",
                "Education",
                "Bachelor of Science in Business: Boston University 2020"
        ));

        ResumeParsingService.ParsedResume parsed = service.parse(resume, ".docx").block(Duration.ofSeconds(5));

        assertThat(parsed).isNotNull();
        assertThat(parsed.skills()).contains("Procurement", "Supply Chain", "Inventory Management", "Excel", "Power BI", "SAP");
        assertThat(parsed.experience()).hasSize(1);
        assertThat(parsed.experience().get(0))
                .containsEntry("title", "Operations Analyst")
                .containsEntry("company", "Acme Manufacturing")
                .containsEntry("location", "Boston, MA")
                .containsEntry("current", true);
        assertThat(((Number) parsed.parsedProfile().get("totalExperienceMonths")).intValue()).isGreaterThan(60);
        assertThat(parsed.parsedProfile().get("skillEvidence").toString()).contains("Power BI", "experience");
    }

    private ByteArrayResource docxResource(List<String> lines) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            for (String line : lines) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(outputStream);
            return new ByteArrayResource(outputStream.toByteArray());
        }
    }
}
