package com.airral.repository;

import com.airral.domain.Organization;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OrganizationRepository extends R2dbcRepository<Organization, Long> {

    // Count total organizations that have at least one open job (public - for statistics)
    @Query("SELECT COUNT(DISTINCT organization_id) FROM jobs WHERE status = 'OPEN'")
    Mono<Long> countOrganizationsWithOpenJobs();

    /**
     * Whether another company has already proven this domain. Unique among
     * VERIFIED companies only (V36): an unproven claim to a domain must never
     * lock the real company out.
     */
    @Query("SELECT EXISTS (SELECT 1 FROM organizations WHERE lower(domain) = lower(:domain) "
            + "AND verification_status = 'VERIFIED' AND id <> :excludeId)")
    Mono<Boolean> existsVerifiedDomainOtherThan(String domain, Long excludeId);
}
