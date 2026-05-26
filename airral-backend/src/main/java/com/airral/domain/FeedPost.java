package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("feed_posts")
public class FeedPost {

    @Id
    private Long id;

    private Long organizationId;
    private String authorType;
    private String authorDisplayName;

    // COMPANY_SIGNAL | HIRING_PULSE | ROLE_SPOTLIGHT | COMMUNITY_TIP
    // CAREER_UPDATE | JOB_SEARCH_ASK | INTERVIEW_NOTE | SALARY_INTEL | REFERRAL_OFFER | FOUNDER_UPDATE
    private String postType;

    // PUBLIC | AUTHENTICATED | APPLICANTS_ONLY
    private String visibility;

    private String topic;
    private String content;

    private Long linkedJobId;
    private String linkedExternalJobKey;
    private String targetType;
    private String targetLabel;
    private Long authorId;
    private String moderationStatus;
    private Integer reportCount;
    private String hiddenReason;

    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
