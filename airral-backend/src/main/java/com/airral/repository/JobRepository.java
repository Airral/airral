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

    // Search open jobs (public - for job board)
    @Query("""
            SELECT j.*
            FROM jobs j
            LEFT JOIN organizations o ON o.id = j.organization_id
            WHERE j.status = 'OPEN'
              AND (
                LOWER(COALESCE(j.title, '')) LIKE :query
                OR LOWER(COALESCE(j.description, '')) LIKE :query
                OR LOWER(COALESCE(j.department, '')) LIKE :query
                OR LOWER(COALESCE(j.location, '')) LIKE :query
                OR LOWER(COALESCE(o.name, '')) LIKE :query
              )
            ORDER BY j.created_at DESC
            """)
    Flux<Job> searchOpenJobs(String query);

    // Filter open jobs by department (public - for job board)
    @Query("""
            SELECT *
            FROM jobs
            WHERE status = 'OPEN'
              AND LOWER(COALESCE(department, '')) = :department
            ORDER BY created_at DESC
            """)
    Flux<Job> findOpenJobsByDepartment(String department);

    // Search open jobs within a department (public - for job board)
    @Query("""
            SELECT j.*
            FROM jobs j
            LEFT JOIN organizations o ON o.id = j.organization_id
            WHERE j.status = 'OPEN'
              AND LOWER(COALESCE(j.department, '')) = :department
              AND (
                LOWER(COALESCE(j.title, '')) LIKE :query
                OR LOWER(COALESCE(j.description, '')) LIKE :query
                OR LOWER(COALESCE(j.location, '')) LIKE :query
                OR LOWER(COALESCE(o.name, '')) LIKE :query
              )
            ORDER BY j.created_at DESC
            """)
    Flux<Job> searchOpenJobsByDepartment(String query, String department);

    // Find one open job for public job detail pages
    @Query("SELECT * FROM jobs WHERE id = :id AND status = 'OPEN'")
    Mono<Job> findOpenJobById(Long id);

    // Find job by ID and organization (security check)
    @Query("SELECT * FROM jobs WHERE id = :id AND organization_id = :organizationId")
    Mono<Job> findByIdAndOrganizationId(Long id, Long organizationId);

    // Count jobs by organization
    @Query("SELECT COUNT(*) FROM jobs WHERE organization_id = :organizationId")
    Mono<Long> countByOrganizationId(Long organizationId);

    // Count open jobs by organization
    @Query("SELECT COUNT(*) FROM jobs WHERE organization_id = :organizationId AND status = 'OPEN'")
    Mono<Long> countOpenJobsByOrganizationId(Long organizationId);

    // Count total open jobs (public - for statistics)
    @Query("SELECT COUNT(*) FROM jobs WHERE status = 'OPEN'")
    Mono<Long> countOpenJobs();
}
