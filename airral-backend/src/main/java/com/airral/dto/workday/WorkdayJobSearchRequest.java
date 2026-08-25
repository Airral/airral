package com.airral.dto.workday;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class WorkdayJobSearchRequest {
    @Builder.Default
    private Map<String, Object> appliedFacets = Map.of();
    private int limit;
    private int offset;
    @Builder.Default
    private String searchText = "";
}
