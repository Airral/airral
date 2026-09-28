package com.airral.repository;

import com.airral.domain.InterviewKit;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface InterviewKitRepository extends R2dbcRepository<InterviewKit, Long> {

    @Query("SELECT * FROM interview_kits WHERE organization_id = :organizationId ORDER BY lower(name)")
    Flux<InterviewKit> findByOrganizationId(Long organizationId);

    @Query("SELECT * FROM interview_kits WHERE id = :id AND organization_id = :organizationId")
    Mono<InterviewKit> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Whether another of the company's kits has this name, ignoring case. Pass -1 when there is no kit to skip. */
    @Query("SELECT EXISTS (SELECT 1 FROM interview_kits WHERE organization_id = :organizationId " +
           "AND lower(name) = lower(:name) AND id <> :exceptId)")
    Mono<Boolean> nameTaken(Long organizationId, String name, Long exceptId);
}
