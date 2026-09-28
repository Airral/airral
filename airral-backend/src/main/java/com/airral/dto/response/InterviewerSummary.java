package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A teammate on an interview. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewerSummary {
    private Long id;
    private String name;
}
