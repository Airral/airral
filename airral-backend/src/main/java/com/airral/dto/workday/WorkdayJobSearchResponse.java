package com.airral.dto.workday;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkdayJobSearchResponse {
    private Integer total;
    private List<WorkdayJobPosting> jobPostings;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkdayJobPosting {
        private String title;
        private String externalPath;
        private String locationsText;
        private String postedOn;
        private String remoteType;
        private List<String> bulletFields;
    }
}
