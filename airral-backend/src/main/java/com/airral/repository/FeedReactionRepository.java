package com.airral.repository;

import com.airral.domain.FeedReaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface FeedReactionRepository extends R2dbcRepository<FeedReaction, Long> {

    Mono<FeedReaction> findByPostIdAndUserId(Long postId, Long userId);

    @Query("SELECT COUNT(*) FROM feed_reactions WHERE post_id = :postId AND reaction_type = :reactionType")
    Mono<Long> countByPostIdAndReactionType(Long postId, String reactionType);

    Mono<Void> deleteByPostIdAndUserId(Long postId, Long userId);
}
