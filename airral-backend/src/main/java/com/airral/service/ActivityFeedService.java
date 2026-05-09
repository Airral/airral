package com.airral.service;

import com.airral.domain.ActivityFeed;
import com.airral.dto.response.ActivityFeedResponse;
import com.airral.repository.ActivityFeedRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ActivityFeedService {

    private final ActivityFeedRepository activityFeedRepository;

    public ActivityFeedService(ActivityFeedRepository activityFeedRepository) {
        this.activityFeedRepository = activityFeedRepository;
    }

    /**
     * Create a new activity
     */
    public Mono<ActivityFeed> createActivity(Long organizationId, String activityType, String title,
                                            String description, Long actorId, String actorName,
                                            String actorRole, String targetType, Long targetId,
                                            String targetName, String actionUrl) {
        ActivityFeed activity = ActivityFeed.builder()
                .organizationId(organizationId)
                .activityType(activityType)
                .title(title)
                .description(description)
                .actionUrl(actionUrl)
                .actorId(actorId)
                .actorName(actorName)
                .actorRole(actorRole)
                .targetType(targetType)
                .targetId(targetId)
                .targetName(targetName)
                .isPublic(true)
                .priority("MEDIUM")
                .category(getCategoryFromType(activityType))
                .icon(getIconFromType(activityType))
                .activityAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build();

        return activityFeedRepository.save(activity);
    }

    /**
     * Get recent activities
     */
    public Flux<ActivityFeedResponse> getRecentActivities(Long organizationId, int limit) {
        return activityFeedRepository.findRecentByOrganizationId(organizationId, limit)
                .map(this::toActivityFeedResponse);
    }

    /**
     * Get activities by type
     */
    public Flux<ActivityFeedResponse> getActivitiesByType(Long organizationId, String activityType, int limit) {
        return activityFeedRepository.findByOrganizationIdAndActivityType(organizationId, activityType, limit)
                .map(this::toActivityFeedResponse);
    }

    /**
     * Get activities by category
     */
    public Flux<ActivityFeedResponse> getActivitiesByCategory(Long organizationId, String category, int limit) {
        return activityFeedRepository.findByOrganizationIdAndCategory(organizationId, category, limit)
                .map(this::toActivityFeedResponse);
    }

    /**
     * Get activities since a time
     */
    public Flux<ActivityFeedResponse> getActivitiesSince(Long organizationId, LocalDateTime since) {
        return activityFeedRepository.findByOrganizationIdSince(organizationId, since)
                .map(this::toActivityFeedResponse);
    }

    /**
     * Get public activities
     */
    public Flux<ActivityFeedResponse> getPublicActivities(Long organizationId, int limit) {
        return activityFeedRepository.findPublicByOrganizationId(organizationId, limit)
                .map(this::toActivityFeedResponse);
    }

    /**
     * Convert ActivityFeed to ActivityFeedResponse
     */
    private ActivityFeedResponse toActivityFeedResponse(ActivityFeed activity) {
        return ActivityFeedResponse.builder()
                .id(activity.getId())
                .organizationId(activity.getOrganizationId())
                .activityType(activity.getActivityType())
                .title(activity.getTitle())
                .description(activity.getDescription())
                .actionUrl(activity.getActionUrl())
                .actorId(activity.getActorId())
                .actorName(activity.getActorName())
                .actorRole(activity.getActorRole())
                .targetType(activity.getTargetType())
                .targetId(activity.getTargetId())
                .targetName(activity.getTargetName())
                .isPublic(activity.getIsPublic())
                .visibleToRoles(activity.getVisibleToRoles())
                .priority(activity.getPriority())
                .category(activity.getCategory())
                .icon(activity.getIcon())
                .metadata(activity.getMetadata())
                .activityAt(activity.getActivityAt())
                .createdAt(activity.getCreatedAt())
                .build();
    }

    /**
     * Helper: Determine category from activity type
     */
    private String getCategoryFromType(String activityType) {
        if (activityType.startsWith("JOB_") || activityType.startsWith("APPLICATION_") ||
                activityType.startsWith("INTERVIEW_") || activityType.startsWith("OFFER_")) {
            return "HIRING";
        } else if (activityType.startsWith("USER_") || activityType.startsWith("TEAM_")) {
            return "TEAM";
        }
        return "SYSTEM";
    }

    /**
     * Helper: Determine icon from activity type
     */
    private String getIconFromType(String activityType) {
        return switch (activityType) {
            case "JOB_POSTED" -> "briefcase";
            case "APPLICATION_RECEIVED" -> "file-text";
            case "INTERVIEW_SCHEDULED" -> "calendar";
            case "OFFER_SENT" -> "mail";
            case "USER_INVITED" -> "user-plus";
            case "USER_JOINED" -> "user-check";
            default -> "activity";
        };
    }
}
