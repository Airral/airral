package com.airral.dto.bamboohr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BambooHrJobSummaryResponse {
    private Long id;
    private BambooHrLabel title;
    private OffsetDateTime postedDate;
    private BambooHrLabel location;
    private BambooHrLabel department;
    private BambooHrLabel status;
    private String postingUrl;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BambooHrLabel {
        private Long id;
        private String label;
    }
}
