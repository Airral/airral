package com.airral.repository;

import com.airral.domain.CompanyFollow;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CompanyFollowRepository extends R2dbcRepository<CompanyFollow, Long> {

    Mono<CompanyFollow> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    Mono<Boolean> existsByUserIdAndOrganizationId(Long userId, Long organizationId);

    Flux<CompanyFollow> findByUserId(Long userId);

    Mono<Void> deleteByUserIdAndOrganizationId(Long userId, Long organizationId);
}
