package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedSignalResponse {
    private String id;
    private String signalType;
    private String companyName;
    private String headline;
    private String summary;
    private String whyItMatters;
    private String sourceName;
    private String sourceDomain;
    private String sourceUrl;
    private String sourceImageUrl;
    private LocalDateTime publishedAt;
    private String confidence;
    private int linkedJobsCount;
    private List<String> tags;
    private String primaryAction;
}
