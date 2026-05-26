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
@Table("feed_reactions")
public class FeedReaction {

    @Id
    private Long id;

    private Long postId;
    private Long userId;

    // USEFUL | INSPIRING | PRACTICAL
    private String reactionType;

    private LocalDateTime createdAt;
}
