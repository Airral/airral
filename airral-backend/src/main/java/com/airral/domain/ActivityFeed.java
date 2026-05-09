package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * ActivityFeed - Real-time activity stream for the organization
 * Shows recent actions, updates, and notifications across the platform
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("activity_feed")
public class ActivityFeed {

    @Id
    private Long id;

    // Multi-tenant
    private Long organizationId;

    // Activity details
    private String activityType; // JOB_POSTED, APPLICATION_RECEIVED, INTERVIEW_SCHEDULED, etc.
    private String title;
    private String description;
    private String actionUrl; // Deep link to the resource

    // Actor - who did it
    private Long actorId; // User who performed the action
    private String actorName;
    private String actorRole;

    // Target - what was affected
    private String targetType; // JOB, APPLICATION, INTERVIEW, OFFER, USER
    private Long targetId; // ID of the affected resource
    private String targetName; // Name/title of the affected resource

    // Visibility
    private Boolean isPublic; // Visible to all org members or just specific roles
    private String visibleToRoles; // Comma-separated roles (HR_MANAGER,ADMIN)

    // Metadata
    private String priority; // LOW, MEDIUM, HIGH
    private String category; // HIRING, TEAM, SYSTEM
    private String icon; // Icon name for UI
    private String metadata; // JSON for additional data

    // Timestamps
    private LocalDateTime activityAt; // When the activity occurred
    private LocalDateTime createdAt;
}
