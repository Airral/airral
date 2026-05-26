package com.airral.repository;

import com.airral.domain.CandidateProfile;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface CandidateProfileRepository extends R2dbcRepository<CandidateProfile, Long> {

    Mono<CandidateProfile> findByUserId(Long userId);

    Mono<Boolean> existsByUserId(Long userId);
}
