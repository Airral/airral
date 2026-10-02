package com.airral.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

class SearchJobsToolTest {

    /** Records each search it is asked for and answers with a fixed result. */
    private static class FakeCatalog implements JobCatalogPort {
        final List<Object[]> asked = new ArrayList<>();
        List<CandidateJobSummaryResponse> answer = List.of();

        @Override
        public Mono<List<CandidateJobSummaryResponse>> search(
                String query, String location, String workMode, String company, boolean salaryListed, int limit) {
            asked.add(new Object[] {query, location, workMode, company, salaryListed, limit});
            return Mono.just(answer);
        }

        @Override
        public Mono<CandidateJobDetailResponse> detail(String sourceType, String boardToken, String externalJobId) {
            return Mono.empty();
        }
    }

    private static CandidateJobSummaryResponse nurse() {
        return CandidateJobSummaryResponse.builder()
                .title("Registered Nurse")
                .companyName("Baylor Scott & White")
                .location("Dallas, TX")
                .workMode("ONSITE")
                .employmentType("Full-time")
                .salaryLabel("$38-$52/hr")
                .postedLabel("2 days ago")
                .sourceType("WORKDAY")
                .sourceBoardToken("bswhealth")
                .externalJobId("R-1001")
                .applyUrl("https://example.com/apply/R-1001")
                .build();
    }

    private static String call(SearchJobsTool tool, String arguments) throws Exception {
        return tool.call(new McpCaller(1L, null, "APPLICANT"), new ObjectMapper().readTree(arguments)).block();
    }

    // ── schema ──

    @Test
    @DisplayName("the schema offers salary_listed as an optional boolean, described like the others")
    @SuppressWarnings("unchecked")
    void schemaOffersSalaryListed() {
        Map<String, Object> schema = new SearchJobsTool(new FakeCatalog()).inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> salaryListed = (Map<String, Object>) properties.get("salary_listed");

        assertEquals("boolean", salaryListed.get("type"));
        String description = String.valueOf(salaryListed.get("description"));
        assertTrue(description.startsWith("Optional."), description);
        assertTrue(description.contains("pay"), description);
        assertEquals(List.of("query"), schema.get("required"), "only the query is required");
    }

    // ── arguments reach the catalog ──

    @Test
    @DisplayName("work mode, location, company and salary_listed are passed on as given")
    void filtersArePassedOn() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        call(new SearchJobsTool(catalog), """
                {"query":"data analyst","work_mode":"REMOTE","location":"TX",
                 "company":"Vanta","salary_listed":true,"limit":25}
                """);

        assertEquals(List.of("data analyst", "TX", "REMOTE", "Vanta", true, 25),
                List.of(catalog.asked.get(0)));
    }

    @Test
    @DisplayName("salary_listed is off unless asked for, and a quoted \"true\" still counts")
    void salaryListedDefaultsOff() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        SearchJobsTool tool = new SearchJobsTool(catalog);

        call(tool, "{\"query\":\"nurse\"}");
        call(tool, "{\"query\":\"nurse\",\"salary_listed\":false}");
        call(tool, "{\"query\":\"nurse\",\"salary_listed\":\"true\"}");

        assertEquals(false, catalog.asked.get(0)[4]);
        assertEquals(false, catalog.asked.get(1)[4]);
        assertEquals(true, catalog.asked.get(2)[4]);
    }

    // ── rendering ──

    @Test
    @DisplayName("a result renders exactly as before, so an agent parsing it is unaffected")
    void resultFormatIsUnchanged() throws Exception {
        FakeCatalog catalog = new FakeCatalog();
        catalog.answer = List.of(nurse());

        String text = call(new SearchJobsTool(catalog), "{\"query\":\"nurse\",\"location\":\"TX\"}");

        assertEquals("""
                1 posting matching "nurse":

                — Registered Nurse · Baylor Scott & White
                  Location: Dallas, TX
                  Work mode: ONSITE
                  Type: Full-time
                  Pay: $38-$52/hr
                  Posted: 2 days ago
                  For the full description, call get_job with source_type=WORKDAY, board_token=bswhealth, job_id=R-1001
                  Apply: https://example.com/apply/R-1001
                """, text);
    }

    @Test
    @DisplayName("no matches names the salary filter among the ones to drop")
    void emptyResultMentionsSalary() throws Exception {
        String text = call(new SearchJobsTool(new FakeCatalog()),
                "{\"query\":\"pharmacist\",\"salary_listed\":true}");

        assertEquals("No live postings matched \"pharmacist\". Try broader wording, "
                + "or drop the location, work mode or salary filter.", text);
    }
}
