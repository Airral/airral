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
public class NewsArticleResponse {
    private String id;
    private String provider;
    private String category;
    private String signalType;
    private String title;
    private String summary;
    private String whyItMatters;
    private String displayContext;
    private String sourceName;
    private String sourceDomain;
    private String sourceType;
    private String sourceTrustTier;
    private String sourceHomeUrl;
    private String sourceUrl;
    private String canonicalUrl;
    private String imageUrl;
    private String imageAltText;
    private String byline;
    private String language;
    private String country;
    private LocalDateTime publishedAt;
    private Integer relevanceScore;
    private Integer freshnessScore;
    private String primaryAction;
    private List<String> matchedKeywords;
    private List<String> tags;
}
