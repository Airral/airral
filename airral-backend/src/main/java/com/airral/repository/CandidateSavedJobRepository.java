package com.airral.repository;

import com.airral.domain.CandidateSavedJob;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CandidateSavedJobRepository extends R2dbcRepository<CandidateSavedJob, Long> {

    @Query("SELECT * FROM candidate_saved_jobs WHERE user_id = :userId ORDER BY updated_at DESC")
    Flux<CandidateSavedJob> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Mono<CandidateSavedJob> findByIdAndUserId(Long id, Long userId);

    Mono<CandidateSavedJob> findByUserIdAndSourceJobKey(Long userId, String sourceJobKey);

    @Query("DELETE FROM candidate_saved_jobs WHERE id = :id AND user_id = :userId")
    Mono<Void> deleteByIdAndUserId(Long id, Long userId);
}
