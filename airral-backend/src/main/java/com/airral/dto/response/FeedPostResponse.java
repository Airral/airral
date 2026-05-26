package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedPostResponse {

    private Long id;

    // Company info (joined from organizations)
    private Long organizationId;
    private String companyName;
    private String companyHandle;

    private String authorType;
    private Long authorId;
    private String authorDisplayName;

    private String postType;
    private String visibility;
    private String topic;
    private String content;

    private Long linkedJobId;
    private String linkedExternalJobKey;
    private String targetType;
    private String targetLabel;
    private String moderationStatus;
    private Integer reportCount;

    // Engagement counts
    private long usefulCount;
    private long inspiringCount;
    private long practicalCount;
    private long commentCount;

    // Current viewer's reaction (null if not reacted or unauthenticated)
    private String viewerReaction;

    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
}
