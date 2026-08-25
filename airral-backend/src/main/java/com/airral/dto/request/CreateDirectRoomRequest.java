package com.airral.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateDirectRoomRequest {
    private Long recipientUserId;
    private String initialMessage;
}
