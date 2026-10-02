package com.airral.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;

import reactor.core.publisher.Flux;

/**
 * The location predicate exists for the MCP search_jobs tool only. The website
 * shares this query, so with no location it must be the same SQL with the same
 * bindings as before, and with one it must narrow in SQL rather than leave the
 * caller to filter whatever the newest rows happened to be.
 */
class LocationFilterQueryTest {

    private record Captured(List<String> sql, List<String> bindings) {
        String search() {
            return sql.stream().filter(s -> s.contains("JOIN external_companies c ON c.id = p.company_id")).findFirst().orElseThrow();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Captured run(Function<ExternalJobPostingStore, Flux<?>> search) {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec rows = mock(RowsFetchSpec.class);
        List<String> sql = new ArrayList<>();
        when(databaseClient.sql(anyString())).thenAnswer(invocation -> {
            sql.add(invocation.getArgument(0));
            return spec;
        });
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.map(any(BiFunction.class))).thenReturn(rows);
        when(rows.all()).thenReturn(Flux.empty());

        search.apply(new ExternalJobPostingStore(databaseClient, new CompanyLogoService(""))).blockLast();

        List<String> bindings = mockingDetails(spec).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("bind"))
                .map(invocation -> invocation.getArgument(0) + "="
                        // The cutoff is now() minus the window, so it differs run to run.
                        + ("sourceCutoff".equals(invocation.getArgument(0)) ? "<now-60d>" : invocation.getArgument(1)))
                .toList();
        return new Captured(sql, bindings);
    }

    private static final ExplicitJobFilters REMOTE_WITH_PAY = new ExplicitJobFilters("REMOTE", true, null, null);

    @Test
    @DisplayName("without a location, the query and its bindings are the website's, unchanged")
    void noLocationLeavesTheWebQueryAlone() {
        Captured web = run(store -> store.findRecommendedJobs(
                "all", null, 51, 0, 60, "engineer", null, REMOTE_WITH_PAY));
        Captured noLocation = run(store -> store.findRecommendedJobs(
                "all", null, 51, 0, 60, "engineer", null, REMOTE_WITH_PAY, null));
        Captured blankLocation = run(store -> store.findRecommendedJobs(
                "all", null, 51, 0, 60, "engineer", null, REMOTE_WITH_PAY, "  "));

        assertThat(web.search()).doesNotContain("p.location) LIKE");
        assertThat(web.bindings()).noneMatch(binding -> binding.startsWith("loc"));
        assertThat(noLocation).isEqualTo(web);
        assertThat(blankLocation).isEqualTo(web);
    }

    @Test
    @DisplayName("a city narrows in SQL, before the limit is taken")
    void locationIsAPredicate() {
        Captured captured = run(store -> store.findRecommendedJobs(
                "all", null, 25, 0, 60, "engineer", null, REMOTE_WITH_PAY, "  London "));

        String search = captured.search();
        assertThat(search).contains(" AND LOWER(p.location) LIKE :locCity");
        // In the WHERE clause, with the other filters -- not after the page.
        assertThat(search.indexOf(":locCity")).isLessThan(search.indexOf("ORDER BY"));
        assertThat(search.indexOf("p.work_mode = 'REMOTE'")).isLessThan(search.indexOf(":locCity"));
        assertThat(captured.bindings()).contains("locCity=%london%", "limit=25");
    }

    @Test
    @DisplayName("a state is its name or its abbreviation in capitals, never a word inside another")
    void stateIsNameOrAbbreviation() {
        Captured captured = run(store -> store.findRecommendedJobs(
                "all", null, 25, 0, 60, "nurse", null, ExplicitJobFilters.none(), "Denver, CO"));

        String search = captured.search();
        assertThat(search).contains("LOWER(p.location) LIKE :locCity");
        assertThat(search).contains("LOWER(p.location) LIKE :locStateName");
        assertThat(search).contains("p.location ~ :locStateAbbr");
        assertThat(captured.bindings()).contains("locCity=%denver%", "locStateName=%colorado%");
    }
}
