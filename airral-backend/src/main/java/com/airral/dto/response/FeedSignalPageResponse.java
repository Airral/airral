package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedSignalPageResponse {
    private List<FeedSignalResponse> items;
    private int page;
    private int pageSize;
    private long totalItems;
    private boolean hasNext;
    private String provider;
    private String query;
}
