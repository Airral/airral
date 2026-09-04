package com.airral.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.security.ApiKeyScopes;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * One posting in full, for when a search result is worth reading properly.
 */
@Component
public class GetJobTool implements McpTool {

    /**
     * Descriptions run to tens of thousands of characters on some boards, and
     * all of it is spent from the model's context. This keeps the useful part
     * -- responsibilities and requirements are near the top; the equal
     * opportunity boilerplate is not.
     */
    private static final int MAX_DESCRIPTION_CHARS = 6000;

    private final JobCatalogPort catalog;

    public GetJobTool(JobCatalogPort catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "get_job";
    }

    @Override
    public String description() {
        return """
                Read one job posting in full, including the description and \
                requirements. Use the source_type, board_token and job_id from a \
                search_jobs result. Use this before advising whether someone \
                should apply, or to tailor a CV to a specific role.""";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> sourceType = new LinkedHashMap<>();
        sourceType.put("type", "string");
        sourceType.put("description", "From a search_jobs result, for example GREENHOUSE or LEVER.");

        Map<String, Object> boardToken = new LinkedHashMap<>();
        boardToken.put("type", "string");
        boardToken.put("description", "The company's board identifier, from a search_jobs result.");

        Map<String, Object> jobId = new LinkedHashMap<>();
        jobId.put("type", "string");
        jobId.put("description", "The posting's external id, from a search_jobs result.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source_type", sourceType);
        properties.put("board_token", boardToken);
        properties.put("job_id", jobId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("source_type", "board_token", "job_id"));
        return schema;
    }

    @Override
    public String requiredScope() {
        return ApiKeyScopes.JOBS_READ;
    }

    @Override
    public Mono<String> call(JsonNode arguments) {
        String sourceType = text(arguments, "source_type");
        String boardToken = text(arguments, "board_token");
        String jobId = text(arguments, "job_id");

        if (sourceType == null || boardToken == null || jobId == null) {
            return Mono.just("get_job needs source_type, board_token and job_id. "
                    + "All three appear in each search_jobs result.");
        }

        return catalog.detail(sourceType, boardToken, jobId)
                .map(this::render)
                .defaultIfEmpty("No posting found for that source_type, board_token and job_id. "
                        + "It may have been filled and removed since the search. "
                        + "Run search_jobs again to get current results.");
    }

    private String render(CandidateJobDetailResponse job) {
        StringBuilder out = new StringBuilder();
        out.append(nullSafe(job.getTitle()));
        if (job.getCompanyName() != null) {
            out.append(" · ").append(job.getCompanyName());
        }
        out.append("\n\n");

        appendField(out, "Location", job.getLocation());
        appendField(out, "Work mode", job.getWorkMode());
        appendField(out, "Employment type", job.getEmploymentType());
        appendField(out, "Pay", job.getSalaryLabel());
        appendField(out, "Department", job.getDepartment());
        appendField(out, "Posted", job.getPostedLabel());
        appendField(out, "Apply", job.getApplyUrl());

        String description = job.getDescriptionText();
        if (description != null && !description.isBlank()) {
            out.append("\nDescription:\n");
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                out.append(description, 0, MAX_DESCRIPTION_CHARS)
                        // Said explicitly, so the model does not treat a cut-off
                        // sentence as the end of the requirements.
                        .append("\n\n[Description truncated. Open the apply link for the rest.]");
            } else {
                out.append(description);
            }
        }
        return out.toString();
    }

    private static void appendField(StringBuilder out, String label, String value) {
        if (value != null && !value.isBlank()) {
            out.append(label).append(": ").append(value).append('\n');
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
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText.isBlank() ? null : asText;
    }
}
