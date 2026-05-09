package com.airral.repository;

import com.airral.domain.HrEncounter;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface HrEncounterRepository extends R2dbcRepository<HrEncounter, Long> {

    // Find all encounters for an organization
    @Query("SELECT * FROM hr_encounters WHERE organization_id = :organizationId ORDER BY encountered_at DESC")
    Flux<HrEncounter> findByOrganizationId(Long organizationId);

    // Find encounters for a specific application
    @Query("SELECT * FROM hr_encounters WHERE application_id = :applicationId ORDER BY encountered_at ASC")
    Flux<HrEncounter> findByApplicationId(Long applicationId);

    // Find encounters for a specific job
    @Query("SELECT * FROM hr_encounters WHERE job_id = :jobId ORDER BY encountered_at DESC")
    Flux<HrEncounter> findByJobId(Long jobId);

    // Find encounters by type
    @Query("SELECT * FROM hr_encounters WHERE organization_id = :organizationId AND encounter_type = :encounterType ORDER BY encountered_at DESC")
    Flux<HrEncounter> findByOrganizationIdAndEncounterType(Long organizationId, String encounterType);

    // Find encounters performed by a user
    @Query("SELECT * FROM hr_encounters WHERE performed_by_id = :userId ORDER BY encountered_at DESC")
    Flux<HrEncounter> findByPerformedById(Long userId);

    // Find recent encounters
    @Query("SELECT * FROM hr_encounters WHERE organization_id = :organizationId AND encountered_at >= :since ORDER BY encountered_at DESC LIMIT :limit")
    Flux<HrEncounter> findRecentByOrganizationId(Long organizationId, java.time.LocalDateTime since, int limit);

    // Find by ID and organization (security check)
    @Query("SELECT * FROM hr_encounters WHERE id = :id AND organization_id = :organizationId")
    Mono<HrEncounter> findByIdAndOrganizationId(Long id, Long organizationId);
}
