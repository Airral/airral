package com.airral.repository;

import com.airral.domain.Interview;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface InterviewRepository extends R2dbcRepository<Interview, Long> {

    // Find interviews by application
    @Query("SELECT * FROM interviews WHERE application_id = :applicationId ORDER BY interview_date ASC")
    Flux<Interview> findByApplicationId(Long applicationId);

    // Find all interviews for an organization (via application -> job join)
    @Query("SELECT i.* FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "ORDER BY i.interview_date DESC")
    Flux<Interview> findAllByOrganizationId(Long organizationId);

    // Find upcoming interviews (scheduled and in future)
    @Query("SELECT i.* FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "AND i.status = 'SCHEDULED' " +
           "AND i.interview_date > :now " +
           "ORDER BY i.interview_date ASC")
    Flux<Interview> findUpcomingByOrganizationId(Long organizationId, LocalDateTime now);

    // Find interviews by date range (for calendar view)
    @Query("SELECT i.* FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "AND i.interview_date BETWEEN :startDate AND :endDate " +
           "ORDER BY i.interview_date ASC")
    Flux<Interview> findByOrganizationIdAndDateRange(Long organizationId, 
                                                     LocalDateTime startDate, 
                                                     LocalDateTime endDate);

    // Find interview by ID with organization check
    @Query("SELECT i.* FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE i.id = :id AND j.organization_id = :organizationId")
    Mono<Interview> findByIdAndOrganizationId(Long id, Long organizationId);

    // Count interviews by organization
    @Query("SELECT COUNT(*) FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId")
    Mono<Long> countByOrganizationId(Long organizationId);

    // Count upcoming interviews
    @Query("SELECT COUNT(*) FROM interviews i " +
           "JOIN applications a ON i.application_id = a.id " +
           "JOIN jobs j ON a.job_id = j.id " +
           "WHERE j.organization_id = :organizationId " +
           "AND i.status = 'SCHEDULED' " +
           "AND i.interview_date > :now")
    Mono<Long> countUpcomingByOrganizationId(Long organizationId, LocalDateTime now);
}
