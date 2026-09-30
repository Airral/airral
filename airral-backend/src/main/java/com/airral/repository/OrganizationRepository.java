package com.airral.repository;

import com.airral.domain.Organization;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OrganizationRepository extends R2dbcRepository<Organization, Long> {

    // Count total organizations that have at least one open job (public - for statistics)
    @Query("SELECT COUNT(DISTINCT j.organization_id) FROM jobs j " + JobRepository.PUBLISHED + "WHERE j.status = 'OPEN'")
    Mono<Long> countOrganizationsWithOpenJobs();

    /**
     * Whether another company has already proven this domain. Unique among
     * VERIFIED companies only (V36): an unproven claim to a domain must never
     * lock the real company out.
     */
    @Query("SELECT EXISTS (SELECT 1 FROM organizations WHERE lower(domain) = lower(:domain) "
            + "AND verification_status = 'VERIFIED' AND id <> :excludeId)")
    Mono<Boolean> existsVerifiedDomainOtherThan(String domain, Long excludeId);

    /**
     * Holds the company's row until the surrounding transaction ends, so two
     * changes to its HR managers cannot both pass the "keep one" check.
     * NO KEY UPDATE rather than UPDATE: inserts that only reference the company
     * (a new job, a new member) take a key-share lock, which this leaves alone.
     */
    @Query("SELECT id FROM organizations WHERE id = :id FOR NO KEY UPDATE")
    Mono<Long> lockForUpdate(Long id);
}
