package com.airral.repository;

import com.airral.domain.Application;
import com.airral.domain.enums.ApplicationStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ApplicationRepository extends R2dbcRepository<Application, Long> {

    // Find applications by job (with organization security check via join)
    @Query("SELECT a.* FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.id = :jobId AND j.organization_id = :organizationId " +
           "ORDER BY a.applied_at DESC")
    Flux<Application> findByJobIdAndOrganizationId(Long jobId, Long organizationId);

    // Find all applications for an organization
    @Query("SELECT a.* FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "ORDER BY a.applied_at DESC")
    Flux<Application> findAllByOrganizationId(Long organizationId);

    // Find applications by applicant
    @Query("SELECT * FROM applications WHERE applicant_id = :applicantId ORDER BY applied_at DESC")
    Flux<Application> findByApplicantId(Long applicantId);

    // Find applications by applicant email (for external applicants)
    @Query("SELECT * FROM applications WHERE applicant_email = :email ORDER BY applied_at DESC")
    Flux<Application> findByApplicantEmail(String email);

    // Find application by ID with organization security check
    @Query("SELECT a.* FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE a.id = :id AND j.organization_id = :organizationId")
    Mono<Application> findByIdAndOrganizationId(Long id, Long organizationId);

    // Find applications by status for an organization
    @Query("SELECT a.* FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId AND a.status = :status " +
           "ORDER BY a.applied_at DESC")
    Flux<Application> findByOrganizationIdAndStatus(Long organizationId, ApplicationStatus status);

    // Count applications by job
    @Query("SELECT COUNT(*) FROM applications WHERE job_id = :jobId")
    Mono<Long> countByJobId(Long jobId);

    // Count applications by organization
    @Query("SELECT COUNT(*) FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId")
    Mono<Long> countByOrganizationId(Long organizationId);

    // Count new applications (SUBMITTED status) by organization
    @Query("SELECT COUNT(*) FROM applications a " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId AND a.status = 'SUBMITTED'")
    Mono<Long> countNewApplicationsByOrganizationId(Long organizationId);
}
