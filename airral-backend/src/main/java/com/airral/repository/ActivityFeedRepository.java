package com.airral.repository;

import com.airral.domain.ActivityFeed;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ActivityFeedRepository extends R2dbcRepository<ActivityFeed, Long> {

    // Find all activities for an organization
    @Query("SELECT * FROM activity_feed WHERE organization_id = :organizationId ORDER BY activity_at DESC LIMIT :limit")
    Flux<ActivityFeed> findRecentByOrganizationId(Long organizationId, int limit);

    // Find activities by type
    @Query("SELECT * FROM activity_feed WHERE organization_id = :organizationId AND activity_type = :activityType ORDER BY activity_at DESC LIMIT :limit")
    Flux<ActivityFeed> findByOrganizationIdAndActivityType(Long organizationId, String activityType, int limit);

    // Find activities by category
    @Query("SELECT * FROM activity_feed WHERE organization_id = :organizationId AND category = :category ORDER BY activity_at DESC LIMIT :limit")
    Flux<ActivityFeed> findByOrganizationIdAndCategory(Long organizationId, String category, int limit);

    // Find activities by actor
    @Query("SELECT * FROM activity_feed WHERE actor_id = :actorId ORDER BY activity_at DESC LIMIT :limit")
    Flux<ActivityFeed> findByActorId(Long actorId, int limit);

    // Find activities since a specific time
    @Query("SELECT * FROM activity_feed WHERE organization_id = :organizationId AND activity_at >= :since ORDER BY activity_at DESC")
    Flux<ActivityFeed> findByOrganizationIdSince(Long organizationId, java.time.LocalDateTime since);

    // Find public activities
    @Query("SELECT * FROM activity_feed WHERE organization_id = :organizationId AND is_public = true ORDER BY activity_at DESC LIMIT :limit")
    Flux<ActivityFeed> findPublicByOrganizationId(Long organizationId, int limit);

    // Find by ID and organization (security check)
    @Query("SELECT * FROM activity_feed WHERE id = :id AND organization_id = :organizationId")
    Mono<ActivityFeed> findByIdAndOrganizationId(Long id, Long organizationId);
}
