package com.airral.mcp;

import java.time.LocalDateTime;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A company's own jobs and how far their applicants have got, for employer
 * tools. Counts only: no applicant's name, email or resume leaves through here.
 */
public interface CompanyJobsPort {

    /** One job and its pipeline in numbers. */
    record JobStats(
            Long id,
            String title,
            String status,
            String department,
            String location,
            LocalDateTime createdAt,
            long applicants,
            long newApplicants,
            long inReview,
            long interviewing,
            long offers,
            long hired) {
    }

    /**
     * The company's jobs, open ones first, newest first. {@code hiringManagerId}
     * set means only the jobs that person is hiring manager on, which is what a
     * manager sees in the HR portal. {@code status} null means any status.
     */
    Flux<JobStats> jobs(Long organizationId, Long hiringManagerId, String status, int limit);

    Mono<String> companyName(Long organizationId);
}
