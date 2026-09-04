package com.airral.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.security.ApiKeyScopes;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * Free-text search across AIRRAL's job corpus.
 */
@Component
public class SearchJobsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 10;
    /**
     * A tool result is spent from the model's context window, so this is a
     * budget, not a preference. Twenty five postings of this shape is already
     * several thousand tokens.
     */
    private static final int MAX_LIMIT = 25;

    private final JobCatalogPort catalog;

    public SearchJobsTool(JobCatalogPort catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "search_jobs";
    }

    @Override
    public String description() {
        return """
                Search live job postings on AIRRAL by keyword, and optionally narrow \
                by location or work mode. Use this to find roles matching what \
                someone is looking for. Returns a compact summary of each match; \
                call get_job for the full description of one. Postings come from \
                company career sites and are refreshed every few hours.""";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description",
                "Words to search for, such as 'senior backend engineer' or 'product designer'. "
                        + "Matched against job titles, tags and descriptions.");

        Map<String, Object> location = new LinkedHashMap<>();
        location.put("type", "string");
        location.put("description",
                "Optional. Narrows to postings whose location contains this text, "
                        + "such as 'London', 'Toronto' or 'CA'. Omit for anywhere.");

        Map<String, Object> workMode = new LinkedHashMap<>();
        workMode.put("type", "string");
        workMode.put("enum", List.of("REMOTE", "HYBRID", "ONSITE"));
        workMode.put("description", "Optional. Omit for any arrangement.");

        Map<String, Object> company = new LinkedHashMap<>();
        company.put("type", "string");
        company.put("description", "Optional. Restrict to one company by name.");

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        limit.put("description", "How many postings to return. Defaults to " + DEFAULT_LIMIT + ".");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", query);
        properties.put("location", location);
        properties.put("work_mode", workMode);
        properties.put("company", company);
        properties.put("limit", limit);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return schema;
    }

    @Override
    public String requiredScope() {
        return ApiKeyScopes.JOBS_READ;
    }

    @Override
    public Mono<String> call(JsonNode arguments) {
        String query = text(arguments, "query");
        if (query == null || query.isBlank()) {
            // Told plainly, because the model can fix this itself on the next
            // call. An empty result would look like "no such jobs exist".
            return Mono.just("No query was provided. Pass a 'query' describing the role to search for.");
        }

        int limit = clampLimit(arguments);

        return catalog.search(
                        query,
                        text(arguments, "location"),
                        text(arguments, "work_mode"),
                        text(arguments, "company"),
                        limit)
                .map(jobs -> render(query, jobs));
    }

    private int clampLimit(JsonNode arguments) {
        JsonNode node = arguments == null ? null : arguments.get("limit");
        if (node == null || !node.canConvertToInt()) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, node.asInt()));
    }

    private String render(String query, List<CandidateJobSummaryResponse> jobs) {
        if (jobs.isEmpty()) {
            // Distinguishes "nothing matched" from "something broke", and
            // suggests the recovery rather than leaving the model to guess.
            return "No live postings matched \"" + query + "\". Try broader wording, "
                    + "or drop the location or work mode filter.";
        }

        StringBuilder out = new StringBuilder();
        out.append(jobs.size()).append(jobs.size() == 1 ? " posting" : " postings")
                .append(" matching \"").append(query).append("\":\n");

        for (CandidateJobSummaryResponse job : jobs) {
            out.append("\n— ").append(nullSafe(job.getTitle()));
            if (job.getCompanyName() != null) {
                out.append(" · ").append(job.getCompanyName());
            }
            out.append('\n');

            appendField(out, "Location", job.getLocation());
            appendField(out, "Work mode", job.getWorkMode());
            appendField(out, "Type", job.getEmploymentType());
            appendField(out, "Pay", job.getSalaryLabel());
            appendField(out, "Posted", job.getPostedLabel());

            // The id an agent needs for get_job. Named as an instruction rather
            // than a bare value, or the model tends to invent its own key.
            if (job.getSourceType() != null && job.getExternalJobId() != null) {
                out.append("  For the full description, call get_job with source_type=")
                        .append(job.getSourceType())
                        .append(", board_token=").append(nullSafe(job.getSourceBoardToken()))
                        .append(", job_id=").append(job.getExternalJobId())
                        .append('\n');
            }
            if (job.getApplyUrl() != null) {
                out.append("  Apply: ").append(job.getApplyUrl()).append('\n');
            }
        }
        return out.toString();
    }

    private static void appendField(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append("  ").append(label).append(": ").append(value).append('\n');
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
