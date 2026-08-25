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
public class RoomInviteResponse {
    private Long roomId;
    private String inviteToken;
    private LocalDateTime expiresAt;
    private Integer maxUses;
    private Integer usesCount;
}
