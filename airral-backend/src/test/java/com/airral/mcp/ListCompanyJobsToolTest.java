package com.airral.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class ListCompanyJobsToolTest {

    /** Records what it was asked for, so scoping can be checked. */
    private static class FakeCompanyJobs implements CompanyJobsPort {
        final List<Object[]> asked = new ArrayList<>();

        @Override
        public Flux<JobStats> jobs(Long organizationId, Long hiringManagerId, String status, int limit) {
            asked.add(new Object[] {organizationId, hiringManagerId, status, limit});
            return Flux.just(new JobStats(25L, "Warehouse Shift Lead", "OPEN", "Operations", "Austin, TX",
                    LocalDateTime.now().minusDays(3), 2, 1, 0, 1, 0, 0));
        }

        @Override
        public Mono<String> companyName(Long organizationId) {
            return Mono.just("Fieldline");
        }
    }

    private static String call(ListCompanyJobsTool tool, McpCaller caller, String arguments) throws Exception {
        return tool.call(caller, new ObjectMapper().readTree(arguments)).block();
    }

    @Test
    @DisplayName("HR sees the whole company's jobs, scoped to their own company")
    void hrSeesTheCompany() throws Exception {
        FakeCompanyJobs jobs = new FakeCompanyJobs();
        String text = call(new ListCompanyJobsTool(jobs), new McpCaller(91L, 29L, "HR_MANAGER"), "{}");

        assertEquals(29L, jobs.asked.get(0)[0]);
        assertNull(jobs.asked.get(0)[1], "HR is not limited to jobs they manage");
        assertTrue(text.startsWith("Fieldline: 1 job, 1 open."), text);
        assertTrue(text.contains("Warehouse Shift Lead · Open"), text);
        assertTrue(text.contains("Applied 2 · New 1 · In review 0 · Interviewing 1 · Offers 0 · Hired 0"), text);
        assertTrue(text.contains("posted 3 days ago"), text);
    }

    @Test
    @DisplayName("a manager sees only the jobs they are hiring manager on")
    void managerSeesTheirJobs() throws Exception {
        FakeCompanyJobs jobs = new FakeCompanyJobs();
        String text = call(new ListCompanyJobsTool(jobs), new McpCaller(40L, 29L, "MANAGER"), "{}");
        assertEquals(40L, jobs.asked.get(0)[1]);
        assertTrue(text.contains("1 job you are hiring manager on"), text);
    }

    @Test
    @DisplayName("a key with no company reads nothing")
    void noCompanyReadsNothing() throws Exception {
        FakeCompanyJobs jobs = new FakeCompanyJobs();
        String text = call(new ListCompanyJobsTool(jobs), new McpCaller(89L, null, "APPLICANT"), "{}");
        assertTrue(jobs.asked.isEmpty());
        assertTrue(text.contains("not tied to a company"), text);
    }

    @Test
    @DisplayName("status and limit are checked and passed on")
    void argumentsAreChecked() throws Exception {
        FakeCompanyJobs jobs = new FakeCompanyJobs();
        ListCompanyJobsTool tool = new ListCompanyJobsTool(jobs);
        McpCaller hr = new McpCaller(91L, 29L, "HR_MANAGER");

        call(tool, hr, "{\"status\":\"open\",\"limit\":500}");
        assertEquals("OPEN", jobs.asked.get(0)[2]);
        assertEquals(50, jobs.asked.get(0)[3]);

        String refused = call(tool, hr, "{\"status\":\"ARCHIVED\"}");
        assertEquals(1, jobs.asked.size(), "a bad status never reaches the query");
        assertTrue(refused.contains("Status must be one of"), refused);
    }

    @Test
    @DisplayName("the text carries numbers only, never applicant details")
    void numbersOnly() {
        String text = ListCompanyJobsTool.render("Fieldline", null, false, List.of(new CompanyJobsPort.JobStats(
                1L, "Picker", "DRAFT", null, null, null, 0, 0, 0, 0, 0, 0)));
        assertFalse(text.contains("@"));
        assertTrue(text.contains("Picker · Draft"), text);
        assertEquals("Fieldline has no jobs on AIRRAL.", ListCompanyJobsTool.render("Fieldline", null, false, List.of()));
    }

    @Test
    @DisplayName("a manager with no jobs gets a sentence that reads right")
    void managerWithNoJobs() {
        assertEquals("Fieldline has no jobs on AIRRAL that you are hiring manager on.",
                ListCompanyJobsTool.render("Fieldline", null, true, List.of()));
        assertEquals("Fieldline has no open jobs on AIRRAL.",
                ListCompanyJobsTool.render("Fieldline", "OPEN", false, List.of()));
    }
}
