package com.airral.repository;

import com.airral.domain.FeedPost;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface FeedPostRepository extends R2dbcRepository<FeedPost, Long> {

    Flux<FeedPost> findByOrganizationId(Long organizationId);

    @Query("""
        SELECT * FROM feed_posts
        WHERE visibility = 'PUBLIC'
          AND moderation_status = 'APPROVED'
        ORDER BY published_at DESC
        LIMIT :size OFFSET :offset
    """)
    Flux<FeedPost> findPublicFeedPaged(int size, long offset);

    @Query("SELECT COUNT(*) FROM feed_posts WHERE visibility = 'PUBLIC' AND moderation_status = 'APPROVED'")
    Mono<Long> countPublicFeed();

    @Query("""
        SELECT * FROM feed_posts
        WHERE visibility IN ('PUBLIC','AUTHENTICATED','APPLICANTS_ONLY')
          AND moderation_status = 'APPROVED'
        ORDER BY published_at DESC
        LIMIT :size OFFSET :offset
    """)
    Flux<FeedPost> findMemberFeedPaged(int size, long offset);

    @Query("""
        SELECT COUNT(*) FROM feed_posts
        WHERE visibility IN ('PUBLIC','AUTHENTICATED','APPLICANTS_ONLY')
          AND moderation_status = 'APPROVED'
    """)
    Mono<Long> countMemberFeed();

    @Query("""
        SELECT fp.* FROM feed_posts fp
        INNER JOIN company_follows cf ON cf.organization_id = fp.organization_id
        WHERE cf.user_id = :userId AND fp.visibility IN ('PUBLIC','AUTHENTICATED','APPLICANTS_ONLY')
          AND fp.moderation_status = 'APPROVED'
        ORDER BY fp.published_at DESC
        LIMIT :size OFFSET :offset
    """)
    Flux<FeedPost> findFeedForFollower(Long userId, int size, long offset);

    @Query("""
        SELECT COUNT(*) FROM feed_posts
        WHERE author_id = :authorId
          AND author_type = :authorType
          AND created_at >= :since
    """)
    Mono<Long> countRecentByAuthor(Long authorId, String authorType, java.time.LocalDateTime since);
}
