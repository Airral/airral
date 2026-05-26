package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NewsPageResponse {
    private List<NewsArticleResponse> items;
    private int page;
    private int pageSize;
    private long totalItems;
    private boolean hasNext;
    private String provider;
    private String engineVersion;
    private String category;
    private String query;
    private List<String> sourceQueries;
    private List<String> sourceLabels;
    private Boolean cached;
    private Integer cacheTtlSeconds;
    private LocalDateTime generatedAt;
    private LocalDateTime cacheExpiresAt;
}
