package com.airral.repository;

import com.airral.domain.Organization;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface OrganizationRepository extends R2dbcRepository<Organization, Long> {

    Mono<Organization> findByDomain(String domain);

    Mono<Boolean> existsByDomain(String domain);
}
