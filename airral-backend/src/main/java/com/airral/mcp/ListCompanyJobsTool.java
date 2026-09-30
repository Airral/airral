package com.airral.mcp;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.airral.security.ApiKeyScopes;
import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Mono;

/**
 * An employer's own jobs and how each one's hiring is going, in numbers.
 *
 * <p>The first tool over company data, so the rules for the ones after it are
 * set here: scoped to the caller's company on every query, and to their own
 * jobs when they are a manager, exactly as the HR portal scopes them. Counts
 * only -- no applicant's name, email or resume goes to a model through this.
 */
@Component
public class ListCompanyJobsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final List<String> STATUSES = List.of("OPEN", "DRAFT", "CLOSED", "FILLED");

    private final CompanyJobsPort companyJobs;

    public ListCompanyJobsTool(CompanyJobsPort companyJobs) {
        this.companyJobs = companyJobs;
    }

    @Override
    public String name() {
        return "list_company_jobs";
    }

    @Override
    public String description() {
        return """
                List your company's jobs on AIRRAL and how hiring is going on each: \
                how many people applied, how many are new and waiting for review, \
                in review, interviewing, with an offer, and hired. Use this to see \
                which jobs need attention. Open jobs come first. Shows numbers only, \
                never applicants' names or details. A manager sees the jobs they \
                are hiring manager on.""";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("type", "string");
        status.put("enum", STATUSES);
        status.put("description", "Optional. Only jobs with this status. Omit for every job.");

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        limit.put("description", "How many jobs to return. Defaults to " + DEFAULT_LIMIT + ".");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", status);
        properties.put("limit", limit);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public String requiredScope() {
        return ApiKeyScopes.PIPELINE_READ;
    }

    @Override
    public Mono<String> call(McpCaller caller, JsonNode arguments) {
        if (caller == null || caller.organizationId() == null) {
            return Mono.just("This key is not tied to a company, so there are no company jobs to list.");
        }

        String status = text(arguments, "status");
        if (status != null) {
            status = status.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(status)) {
                return Mono.just("Status must be one of " + String.join(", ", STATUSES) + ", or left out.");
            }
        }
        int limit = limit(arguments);
        // A manager works the jobs they hire for; HR sees the whole company.
        Long onlyHiringManager = "MANAGER".equals(caller.role()) ? caller.userId() : null;
        String statusFilter = status;

        return companyJobs.companyName(caller.organizationId())
                .defaultIfEmpty("Your company")
                .flatMap(company -> companyJobs
                        .jobs(caller.organizationId(), onlyHiringManager, statusFilter, limit)
                        .collectList()
                        .map(jobs -> render(company, statusFilter, onlyHiringManager != null, jobs)));
    }

    static String render(String company, String status, boolean managerOnly, List<CompanyJobsPort.JobStats> jobs) {
        String prefix = status == null ? "" : status.toLowerCase(Locale.ROOT) + " ";
        String suffix = managerOnly ? " you are hiring manager on" : "";
        if (jobs.isEmpty()) {
            return company + " has no " + prefix + "jobs on AIRRAL"
                    + (managerOnly ? " that you are hiring manager on" : "") + ".";
        }

        long open = jobs.stream().filter(job -> "OPEN".equals(job.status())).count();
        StringBuilder out = new StringBuilder();
        out.append(company).append(": ").append(jobs.size()).append(' ').append(prefix)
                .append(jobs.size() == 1 ? "job" : "jobs").append(suffix);
        if (status == null) {
            out.append(", ").append(open).append(" open");
        }
        out.append(".\n");

        for (CompanyJobsPort.JobStats job : jobs) {
            out.append("\n— ").append(job.title() == null ? "Untitled job" : job.title())
                    .append(" · ").append(statusLabel(job.status())).append('\n');
            StringBuilder where = new StringBuilder();
            if (job.department() != null && !job.department().isBlank()) {
                where.append(job.department());
            }
            if (job.location() != null && !job.location().isBlank()) {
                where.append(where.isEmpty() ? "" : " · ").append(job.location());
            }
            if (job.createdAt() != null) {
                long days = Math.max(0, Duration.between(job.createdAt(), LocalDateTime.now()).toDays());
                where.append(where.isEmpty() ? "" : " · ").append("posted ")
                        .append(days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago");
            }
            if (!where.isEmpty()) {
                out.append("  ").append(where).append('\n');
            }
            out.append("  Applied ").append(job.applicants())
                    .append(" · New ").append(job.newApplicants())
                    .append(" · In review ").append(job.inReview())
                    .append(" · Interviewing ").append(job.interviewing())
                    .append(" · Offers ").append(job.offers())
                    .append(" · Hired ").append(job.hired()).append('\n');
        }
        return out.toString();
    }

    private static String statusLabel(String status) {
        if (status == null || status.isBlank()) {
            return "Unknown status";
        }
        return status.charAt(0) + status.substring(1).toLowerCase(Locale.ROOT);
    }

    private static int limit(JsonNode arguments) {
        JsonNode value = arguments == null ? null : arguments.get("limit");
        if (value == null || !value.canConvertToInt()) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, value.asInt()));
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
