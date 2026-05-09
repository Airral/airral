package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityFeedResponse {

    private Long id;
    private Long organizationId;

    // Activity details
    private String activityType;
    private String title;
    private String description;
    private String actionUrl;

    // Actor
    private Long actorId;
    private String actorName;
    private String actorRole;

    // Target
    private String targetType;
    private Long targetId;
    private String targetName;

    // Visibility
    private Boolean isPublic;
    private String visibleToRoles;

    // Metadata
    private String priority;
    private String category;
    private String icon;
    private String metadata;

    // Timestamps
    private LocalDateTime activityAt;
    private LocalDateTime createdAt;
}
