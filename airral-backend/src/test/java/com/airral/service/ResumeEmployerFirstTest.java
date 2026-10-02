package com.airral.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Job lines that put the employer before the title.
 *
 * <p>r18 writes "Sunshine Electronics, Brandon FL -- Sales Associate 6/2021 - Jan 2025",
 * and the parser stored the employer as the title and "Brandon FL -- Sales Associate"
 * as the employer. The dates, and so the years, were right; the headline and the
 * roles guessed from the titles were not.
 */
class ResumeEmployerFirstTest {

    private final ResumeParsingService parser = new ResumeParsingService();

    private ResumeParsingService.ParsedResume parse(String... lines) throws Exception {
        List<String> resume = new ArrayList<>(List.of("Kayla Johnson", "kayla.johnson@example.com | (813) 555-0100"));
        resume.addAll(List.of(lines));
        // Twenty words at least, or the parser refuses the text as an unreadable scan.
        resume.addAll(List.of("Summary",
                "Retail sales associate who tracked her own numbers in Excel and built weekly charts "
                        + "for the store manager, now moving into data analysis."));
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : resume) {
                document.createParagraph().createRun().setText(line);
            }
            document.write(out);
            return parser.parse(new ByteArrayResource(out.toByteArray()), ".docx").block(Duration.ofSeconds(5));
        }
    }

    private Map<String, Object> onlyJob(String... lines) throws Exception {
        List<Map<String, Object>> jobs = parse(lines).experience();
        assertThat(jobs).hasSize(1);
        return jobs.get(0);
    }

    @Test
    @DisplayName("r18: employer, place, a double dash, then the title")
    void employerPlaceDoubleDashTitle() throws Exception {
        ResumeParsingService.ParsedResume parsed = parse(
                "Work",
                "Sunshine Electronics, Brandon FL -- Sales Associate 6/2021 - Jan 2025",
                "helped customers pick out laptops, phones and TVs.");

        assertThat(parsed.experience()).singleElement().satisfies(job -> assertThat(job)
                .containsEntry("title", "Sales Associate")
                .containsEntry("company", "Sunshine Electronics")
                .containsEntry("location", "Brandon FL")
                .containsEntry("startDate", "Jun 2021")
                .containsEntry("endDate", "Jan 2025"));
        assertThat(parsed.parsedProfile()).containsEntry("headline", "Sales Associate at Sunshine Electronics");
    }

    @Test
    @DisplayName("employer | title with the dates on the next line")
    void employerPipeTitleThenDates() throws Exception {
        assertThat(onlyJob("Experience", "Brightline Retail | Data Analyst", "Jan 2022 - Present"))
                .containsEntry("title", "Data Analyst")
                .containsEntry("company", "Brightline Retail");
    }

    @Test
    @DisplayName("employer and place, then the title, then the dates, each on its own line")
    void employerLineTitleLineDatesLine() throws Exception {
        assertThat(onlyJob("Experience", "Lakefront Power Tools, Milwaukee, WI", "Mechanical Engineer II", "03/2023 – present"))
                .containsEntry("title", "Mechanical Engineer II")
                .containsEntry("company", "Lakefront Power Tools")
                .containsEntry("location", "Milwaukee, WI");
    }

    @Test
    @DisplayName("a double dash between the dates is a dash")
    void doubleDashDates() throws Exception {
        assertThat(onlyJob("Experience", "Data Analyst | Brightline Retail | Jan 2022 -- Present"))
                .containsEntry("startDate", "Jan 2022")
                .containsEntry("endDate", "Present")
                .containsEntry("current", true);
    }

    @Test
    @DisplayName("a title-first line stays as written, even when the employer's name ends like a title")
    void titleFirstIsNeverSwapped() throws Exception {
        // Each employer ends in a word a title could, and each line is kept by a different
        // rule: "at" is never swapped, a first side with a title word is the title, "Guard"
        // is not a title word, and "Associates" is not "Associate".
        for (String[] job : List.of(
                new String[] {"Tour Guide at Thomas Cook, 2014 - 2016", "Tour Guide", "Thomas Cook"},
                new String[] {"Executive Assistant | Thomas Cook | 2016 - 2019", "Executive Assistant", "Thomas Cook"},
                new String[] {"Boatswain's Mate | US Coast Guard | 2015 - 2019", "Boatswain's Mate", "US Coast Guard"},
                new String[] {"Notary | Smith & Associates | 2019 - 2022", "Notary", "Smith & Associates"},
                new String[] {"Phlebotomist | Quest Diagnostics | 2020 - 2023", "Phlebotomist", "Quest Diagnostics"})) {
            assertThat(onlyJob("Experience", job[0])).as(job[0])
                    .containsEntry("title", job[1])
                    .containsEntry("company", job[2]);
        }
    }
}
