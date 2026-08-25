package com.airral.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRoomRequest {
    private String roomType;
    private String visibility;
    private String name;
    private String description;
    private String topic;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private String initialMessage;
}
