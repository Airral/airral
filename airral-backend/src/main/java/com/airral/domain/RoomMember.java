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
@Table("room_members")
public class RoomMember {

    @Id
    private Long id;

    private Long roomId;
    private Long userId;
    private String memberRole;
    private LocalDateTime joinedAt;
    private LocalDateTime lastReadAt;
    private Boolean muted;
}
