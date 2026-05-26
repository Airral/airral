package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateJobPageResponse {
    private List<CandidateJobSummaryResponse> jobs;
    private int limit;
    private int offset;
    private boolean hasMore;
    private Integer nextOffset;
}
