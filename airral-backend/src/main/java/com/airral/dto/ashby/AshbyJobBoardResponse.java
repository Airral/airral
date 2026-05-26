package com.airral.dto.ashby;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AshbyJobBoardResponse {
    private String apiVersion;
    private List<AshbyJob> jobs;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbyJob {
        private String title;
        private String location;
        private List<AshbySecondaryLocation> secondaryLocations;
        private String department;
        private String team;
        private Boolean isListed;
        private Boolean isRemote;
        private String workplaceType;
        private String descriptionHtml;
        private String descriptionPlain;
        private OffsetDateTime publishedAt;
        private String employmentType;
        private AshbyAddressContainer address;
        private String jobUrl;
        private String applyUrl;
        private AshbyCompensation compensation;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbySecondaryLocation {
        private String location;
        private AshbyPostalAddress address;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbyAddressContainer {
        private AshbyPostalAddress postalAddress;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbyPostalAddress {
        private String addressLocality;
        private String addressRegion;
        private String addressCountry;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbyCompensation {
        private String compensationTierSummary;
        private String scrapeableCompensationSalarySummary;
        private List<AshbyCompensationComponent> summaryComponents;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AshbyCompensationComponent {
        private String compensationType;
        private String interval;
        private String currencyCode;
        private BigDecimal minValue;
        private BigDecimal maxValue;
    }
}
