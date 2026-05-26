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
@Table("feed_comments")
public class FeedComment {

    @Id
    private Long id;

    private Long postId;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
}
