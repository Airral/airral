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
}
