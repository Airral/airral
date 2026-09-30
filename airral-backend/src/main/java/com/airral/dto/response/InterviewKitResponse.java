package com.airral.dto.response;

import com.airral.dto.interview.KitCriterion;
import com.airral.dto.interview.KitQuestion;
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
public class InterviewKitResponse {
    private Long id;
    private String name;
    private String description;
    private Integer durationMinutes;
    private List<KitQuestion> questions;
    private List<KitCriterion> criteria;
    private LocalDateTime updatedAt;
}
