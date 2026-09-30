package com.airral.mcp;

import java.time.LocalDateTime;

import io.r2dbc.spi.Parameters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link CompanyJobsPort} over the database, one grouped query. */
@Component
public class DatabaseCompanyJobs implements CompanyJobsPort {

    private final DatabaseClient databaseClient;

    public DatabaseCompanyJobs(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    @Override
    public Flux<JobStats> jobs(Long organizationId, Long hiringManagerId, String status, int limit) {
        // Withdrawn applications are not counted: the person left the process.
        return databaseClient.sql("""
                        SELECT j.id, j.title, j.status, j.location, j.created_at,
                               COALESCE(d.name, j.department) AS department,
                               COUNT(a.id) FILTER (WHERE a.status <> 'WITHDRAWN') AS applicants,
                               COUNT(a.id) FILTER (WHERE a.status = 'SUBMITTED') AS new_applicants,
                               COUNT(a.id) FILTER (WHERE a.status IN ('UNDER_REVIEW', 'SHORTLISTED')) AS in_review,
                               COUNT(a.id) FILTER (WHERE a.status IN ('INTERVIEW_SCHEDULED', 'INTERVIEWED')) AS interviewing,
                               COUNT(a.id) FILTER (WHERE a.status = 'OFFER_EXTENDED') AS offers,
                               COUNT(a.id) FILTER (WHERE a.status = 'HIRED') AS hired
                        FROM jobs j
                        LEFT JOIN departments d ON d.id = j.department_id
                        LEFT JOIN applications a ON a.job_id = j.id
                        WHERE j.organization_id = :organizationId
                          AND (CAST(:hiringManagerId AS BIGINT) IS NULL OR j.hiring_manager_id = :hiringManagerId)
                          AND (CAST(:status AS VARCHAR) IS NULL OR j.status = :status)
                        GROUP BY j.id, d.name
                        ORDER BY (j.status = 'OPEN') DESC, j.created_at DESC
                        LIMIT :limit
                        """)
                .bind("organizationId", organizationId)
                .bind("hiringManagerId", hiringManagerId == null
                        ? Parameters.in(Long.class) : Parameters.in(hiringManagerId))
                .bind("status", status == null ? Parameters.in(String.class) : Parameters.in(status))
                .bind("limit", limit)
                .map((row, meta) -> new JobStats(
                        row.get("id", Long.class),
                        row.get("title", String.class),
                        row.get("status", String.class),
                        row.get("department", String.class),
                        row.get("location", String.class),
                        row.get("created_at", LocalDateTime.class),
                        count(row.get("applicants", Long.class)),
                        count(row.get("new_applicants", Long.class)),
                        count(row.get("in_review", Long.class)),
                        count(row.get("interviewing", Long.class)),
                        count(row.get("offers", Long.class)),
                        count(row.get("hired", Long.class))))
                .all();
    }

    @Override
    public Mono<String> companyName(Long organizationId) {
        return databaseClient.sql("SELECT name FROM organizations WHERE id = :id")
                .bind("id", organizationId)
                .map((row, meta) -> row.get("name", String.class))
                .one();
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}
