package com.airral.service;

import com.airral.domain.CandidateResumeDocument;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Years of experience read from a resume.
 *
 * <p>A resume listing jobs from 2021 to now was told "2+ years requested; 0 years
 * shown". Ten of the 22 resumes stored locally parsed to no jobs and 0.0 years,
 * and three more lost jobs. Every case below is the shape of one of those real
 * resumes. The parser stored 0 whenever it found no dated job, so "we could not
 * read your dates" reached the candidate as "you have no experience".
 */
class ResumeExperienceYearsTest {

    private final ResumeParsingService parser = new ResumeParsingService();

    private ResumeParsingService.ParsedResume parse(String... lines) throws Exception {
        List<String> resume = new ArrayList<>(List.of("Jordan Lee", "jordan.lee@example.com | (617) 555-0100"));
        resume.addAll(List.of(lines));
        // The parser refuses fewer than 20 words as an unreadable scan. Last, so the
        // heading also closes whatever section the case ended in.
        resume.addAll(List.of("Summary",
                "Analyst who turns messy operational data into clear weekly reporting for sales, "
                        + "operations and finance teams across several regions."));
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : resume) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
            return parser.parse(new ByteArrayResource(out.toByteArray()), ".docx").block(Duration.ofSeconds(5));
        }
    }

    private static double years(ResumeParsingService.ParsedResume parsed) {
        return ((Number) parsed.parsedProfile().get("experienceYears")).doubleValue();
    }

    @Test
    @DisplayName("a numeric date on its own line under the employer is read")
    void numericDateLine() throws Exception {
        // r16, r02 and r07: the most common reason a resume read as zero years.
        ResumeParsingService.ParsedResume parsed = parse(
                "Professional Experience",
                "Mechanical Engineer II",
                "Lakefront Power Tools, Milwaukee, WI",
                "03/2023 – present",
                "Product Development Engineer",
                "Badger Fluid Systems, Madison, WI",
                "06/2020 – 02/2023",
                "Education",
                "B.S. Mechanical Engineering, University of Wisconsin, 2020");

        assertThat(parsed.experience()).hasSize(2);
        assertThat(parsed.experience().get(0))
                .containsEntry("title", "Mechanical Engineer II")
                .containsEntry("company", "Lakefront Power Tools")
                .containsEntry("location", "Milwaukee, WI")
                .containsEntry("startDate", "Mar 2023")
                .containsEntry("endDate", "Present")
                .containsEntry("current", true);
        assertThat(years(parsed)).isGreaterThan(6.0);
    }

    @Test
    @DisplayName("Work History and Driving Experience are experience sections")
    void otherSectionNames() throws Exception {
        // r05 and r07.
        assertThat(parse(
                "Work History",
                "Forklift Operator / Warehouse Associate",
                "Midwest Home Supply Distribution Center - Groveport, OH",
                "03/2022-Current",
                "Skills",
                "Forklift, RF scanner").experience())
                .singleElement()
                .satisfies(job -> assertThat(job)
                        .containsEntry("company", "Midwest Home Supply Distribution Center")
                        .containsEntry("location", "Groveport, OH"));

        assertThat(parse(
                "DRIVING EXPERIENCE",
                "Regional Truck Driver (CDL-A)",
                "Ozark Freightways, Springfield, MO",
                "04/2022 – Present").experience()).hasSize(1);
    }

    @Test
    @DisplayName("comma and dash job lines are read, with or without brackets")
    void commaAndDashLines() throws Exception {
        // Jordan's resume, the one that read "0 years shown", and r12.
        ResumeParsingService.ParsedResume comma = parse(
                "EXPERIENCE",
                "Data Analyst, Brightline Retail (2022 - Present)",
                "- Wrote SQL queries in Snowflake.",
                "Junior Analyst, Harbor Logistics (2021 - 2022)",
                "- Pulled data from PostgreSQL.");
        assertThat(comma.experience()).extracting(job -> job.get("company"))
                .containsExactly("Brightline Retail", "Harbor Logistics");
        assertThat(years(comma)).isGreaterThan(5.0);

        assertThat(parse(
                "Experience",
                "Customer Service Representative II - Alamo Wireless, San Antonio, TX",
                "02/2025 to Present").experience())
                .singleElement()
                .satisfies(job -> assertThat(job)
                        .containsEntry("title", "Customer Service Representative II")
                        .containsEntry("company", "Alamo Wireless"));
    }

    @Test
    @DisplayName("Sept, ALL CAPS and dotted months count")
    void monthSpellings() throws Exception {
        // Each of these found the job but counted it as zero months.
        for (String dates : List.of("Sept 2021 - Present", "SEP 2021 - PRESENT", "Sep. 2021 - Present")) {
            ResumeParsingService.ParsedResume parsed = parse(
                    "Experience", "Data Analyst | Brightline Retail | " + dates);
            assertThat(parsed.experience()).as(dates).singleElement()
                    .satisfies(job -> assertThat(job).containsEntry("startDate", "Sep 2021"));
            assertThat(years(parsed)).as(dates).isGreaterThan(5.0);
        }
    }

    @Test
    @DisplayName("a resume with no Experience heading still has its jobs")
    void noHeading() throws Exception {
        // Maya's resume: name, city, then the jobs.
        ResumeParsingService.ParsedResume parsed = parse(
                "Chicago, IL",
                "Warehouse Associate, Midwest Grocers Distribution Center, Jan 2022 to Present",
                "- Picked 180 cases an hour.",
                "Stock Associate, Jewel-Osco, Mar 2020 to Dec 2021",
                "- Stocked shelves on the overnight shift.",
                "Education",
                "B.S. Business, Northern Illinois University, 2016 - 2020");

        assertThat(parsed.experience()).extracting(job -> job.get("company"))
                .as("the degree has the same shape as a job and must not count as one")
                .containsExactly("Midwest Grocers Distribution Center", "Jewel-Osco");
        assertThat(years(parsed)).isBetween(6.0, 7.5);
    }

    @Test
    @DisplayName("the word projects or education in a bullet does not end the section")
    void sectionWordsInBullets() throws Exception {
        // Every job after such a bullet was dropped: 7.3 years read as 4.8.
        for (String bullet : List.of("- Led projects across three teams.", "- Ran customer education webinars.")) {
            ResumeParsingService.ParsedResume parsed = parse(
                    "Experience",
                    "Data Analyst | Brightline Retail | Jan 2022 - Present",
                    bullet,
                    "Junior Analyst | Harbor Logistics | Jun 2019 - Dec 2021",
                    "- Pulled data.");
            assertThat(parsed.experience()).as(bullet).hasSize(2);
        }
    }

    @Test
    @DisplayName("the last bullet of one job is not the title of the next")
    void sentenceIsNotATitle() throws Exception {
        // r12: Word bullets carry no bullet character.
        ResumeParsingService.ParsedResume parsed = parse(
                "Experience",
                "Customer Service Representative II - Alamo Wireless, San Antonio, TX",
                "02/2025 to Present",
                "Picked by my team lead to help train two new hiring classes.",
                "Customer Service Representative - Hill Country Health Plans",
                "09/2023 to 01/2025");

        assertThat(parsed.experience()).extracting(job -> job.get("title"))
                .containsExactly("Customer Service Representative II", "Customer Service Representative");

        // An employer can end in a full stop; that line is not a sentence.
        assertThat(parse(
                "Experience",
                "Brightline Retail Corp.",
                "Data Analyst",
                "01/2022 - Present").experience()).hasSize(1);
    }

    @Test
    @DisplayName("a dated sentence in a bullet is not a job")
    void datedBulletIsNotAJob() throws Exception {
        ResumeParsingService.ParsedResume parsed = parse(
                "Experience",
                "Financial Analyst | Front Range Brands | Jan 2024 - Present",
                "- Managed budgets, forecasts 2019 - 2021");

        assertThat(parsed.experience()).singleElement()
                .satisfies(job -> assertThat(job).containsEntry("title", "Financial Analyst"));
    }

    @Test
    @DisplayName("no readable dates means unknown years, and the fit says so instead of zero")
    void unknownIsNotZero() throws Exception {
        ResumeParsingService.ParsedResume parsed = parse(
                "Experience", "Data Analyst at Brightline Retail", "- Wrote SQL.");
        assertThat(parsed.parsedProfile())
                .doesNotContainKey("experienceYears")
                .doesNotContainKey("totalExperienceMonths");

        ObjectMapper json = new ObjectMapper();
        CandidateResumeDocument resume = CandidateResumeDocument.builder()
                .extractedText(parsed.extractedText())
                .parsedSkills(Json.of(json.writeValueAsString(parsed.skills())))
                .parsedExperience(Json.of(json.writeValueAsString(parsed.experience())))
                .parsedProfile(Json.of(json.writeValueAsString(parsed.parsedProfile())))
                .build();
        CandidateJobDetailResponse job = CandidateJobDetailResponse.builder()
                .title("Data Analyst")
                .descriptionText("Requirements: 2+ years of experience with SQL.")
                .tags(List.of("SQL"))
                .build();

        assertThat(new ResumeJobFitAnalyzer(json).analyze(resume, job).missingRequirements())
                .contains("Experience: 2+ years requested; not confidently parsed")
                .noneMatch(gap -> gap.contains("0 years shown"));
    }
}
