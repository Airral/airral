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
public class RoomMessageResponse {
    private Long id;
    private Long roomId;
    private String messageType;
    private String body;
    private String senderDisplayName;
    private String senderInitials;
    private Boolean ownMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
