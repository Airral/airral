package com.airral.dto.greenhouse;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GreenhouseJobBoardResponse {
    private List<GreenhouseJob> jobs;
    private GreenhouseMeta meta;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhouseMeta {
        private Integer total;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhouseJob {
        private Long id;

        @JsonProperty("internal_job_id")
        private Long internalJobId;

        private String title;

        @JsonProperty("updated_at")
        private OffsetDateTime updatedAt;

        @JsonProperty("requisition_id")
        private String requisitionId;

        private GreenhouseLocation location;

        @JsonProperty("absolute_url")
        private String absoluteUrl;

        private String language;
        private Object metadata;
        private String content;
        private List<GreenhouseDepartment> departments;
        private List<GreenhouseOffice> offices;

        @JsonProperty("pay_input_ranges")
        private List<GreenhousePayRange> payInputRanges;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhouseLocation {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhouseDepartment {
        private Long id;
        private String name;

        @JsonProperty("parent_id")
        private Long parentId;

        @JsonProperty("child_ids")
        private List<Long> childIds;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhouseOffice {
        private Long id;
        private String name;
        private String location;

        @JsonProperty("parent_id")
        private Long parentId;

        @JsonProperty("child_ids")
        private List<Long> childIds;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GreenhousePayRange {
        @JsonProperty("min_cents")
        private BigDecimal minCents;

        @JsonProperty("max_cents")
        private BigDecimal maxCents;

        @JsonProperty("currency_type")
        private String currencyType;

        private String title;
        private String blurb;
    }
}
