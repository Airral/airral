package com.airral.repository;

import com.airral.domain.FeedComment;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface FeedCommentRepository extends R2dbcRepository<FeedComment, Long> {

    Flux<FeedComment> findByPostIdOrderByCreatedAtAsc(Long postId);

    Mono<Long> countByPostId(Long postId);
}
