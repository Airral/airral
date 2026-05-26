package com.airral.dto.request;

import lombok.Data;

@Data
public class FeedReactionRequest {
    // USEFUL | INSPIRING | PRACTICAL  — send null to remove reaction
    private String reactionType;
}
