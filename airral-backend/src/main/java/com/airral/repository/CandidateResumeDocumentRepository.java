package com.airral.repository;

import com.airral.domain.CandidateResumeDocument;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface CandidateResumeDocumentRepository extends R2dbcRepository<CandidateResumeDocument, Long> {

    Mono<CandidateResumeDocument> findByIdAndUserId(Long id, Long userId);

    @Query("""
            SELECT *
            FROM candidate_resume_documents
            WHERE user_id = :userId
              AND storage_key LIKE CONCAT('%/', :storedFileName)
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Mono<CandidateResumeDocument> findByStoredFileName(Long userId, String storedFileName);

    @Query("SELECT * FROM candidate_resume_documents WHERE user_id = :userId ORDER BY created_at DESC")
    Flux<CandidateResumeDocument> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
