package com.airral.repository;

import com.airral.domain.CandidateJobFitResult;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CandidateJobFitResultRepository extends R2dbcRepository<CandidateJobFitResult, Long> {

    @Query("""
            SELECT *
            FROM candidate_job_fit_results
            WHERE user_id = :userId
              AND source_job_key = :sourceJobKey
            ORDER BY generated_at DESC
            """)
    Flux<CandidateJobFitResult> findByUserIdAndSourceJobKeyOrderByGeneratedAtDesc(Long userId, String sourceJobKey);

    Mono<CandidateJobFitResult> findByIdAndUserId(Long id, Long userId);
}
