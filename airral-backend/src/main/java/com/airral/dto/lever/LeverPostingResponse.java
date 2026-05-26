package com.airral.dto.lever;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LeverPostingResponse {
    private String id;
    private String text;
    private LeverCategories categories;
    private String country;
    private String opening;
    private String openingPlain;
    private String description;
    private String descriptionPlain;
    private String descriptionBody;
    private String descriptionBodyPlain;
    private String additional;
    private String additionalPlain;
    private List<LeverListSection> lists;
    private String hostedUrl;
    private String applyUrl;
    private String workplaceType;
    private LeverSalaryRange salaryRange;
    private String salaryDescriptionPlain;
    private Long createdAt;
    private Long updatedAt;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeverCategories {
        private String location;
        private String commitment;
        private String team;
        private String department;
        private List<String> allLocations;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeverListSection {
        private String text;
        private String content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LeverSalaryRange {
        private String currency;
        private String interval;
        private BigDecimal min;
        private BigDecimal max;
    }
}
