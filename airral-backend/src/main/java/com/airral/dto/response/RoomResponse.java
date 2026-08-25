package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {
    private Long id;
    private String roomType;
    private String visibility;
    private String name;
    private String description;
    private String topic;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private String createdByDisplayName;
    private Boolean member;
    private String memberRole;
    private Integer memberCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<RoomMessageResponse> recentMessages;
}
