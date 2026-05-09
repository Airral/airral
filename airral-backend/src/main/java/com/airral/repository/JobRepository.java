package com.airral.repository;

import com.airral.domain.Job;
import com.airral.domain.enums.JobStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface JobRepository extends R2dbcRepository<Job, Long> {

    // Find all jobs for an organization
    @Query("SELECT * FROM jobs WHERE organization_id = :organizationId ORDER BY created_at DESC")
    Flux<Job> findByOrganizationId(Long organizationId);

    // Find jobs by organization and status
    @Query("SELECT * FROM jobs WHERE organization_id = :organizationId AND status = :status ORDER BY created_at DESC")
    Flux<Job> findByOrganizationIdAndStatus(Long organizationId, JobStatus status);

    // Find open jobs (public - for job board)
    @Query("SELECT * FROM jobs WHERE status = 'OPEN' ORDER BY created_at DESC")
    Flux<Job> findOpenJobs();

    // Find jobs by department
    @Query("SELECT * FROM jobs WHERE organization_id = :organizationId AND department_id = :departmentId ORDER BY created_at DESC")
    Flux<Job> findByOrganizationIdAndDepartmentId(Long organizationId, Long departmentId);

    // Find job by ID and organization (security check)
    @Query("SELECT * FROM jobs WHERE id = :id AND organization_id = :organizationId")
    Mono<Job> findByIdAndOrganizationId(Long id, Long organizationId);

    // Count jobs by organization
    @Query("SELECT COUNT(*) FROM jobs WHERE organization_id = :organizationId")
    Mono<Long> countByOrganizationId(Long organizationId);

    // Count open jobs by organization
    @Query("SELECT COUNT(*) FROM jobs WHERE organization_id = :organizationId AND status = 'OPEN'")
    Mono<Long> countOpenJobsByOrganizationId(Long organizationId);
}
