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
@Table("rooms")
public class Room {

    @Id
    private Long id;

    private String roomType;
    private String visibility;
    private String name;
    private String description;
    private String topic;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private Long createdByUserId;
    private Boolean isActive;
    private Integer memberCount;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
