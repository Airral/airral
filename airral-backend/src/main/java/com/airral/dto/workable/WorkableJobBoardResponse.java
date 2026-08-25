package com.airral.dto.workable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkableJobBoardResponse {
    private String name;
    private String description;
    private List<WorkableJob> jobs;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkableJob {
        private String title;
        private String code;
        private String shortcode;
        private String country;
        private String state;
        private String city;
        private String department;
        private Boolean telecommuting;

        @JsonProperty("published_on")
        private String publishedOn;

        private String url;

        @JsonProperty("application_url")
        private String applicationUrl;

        private String shortlink;

        @JsonProperty("created_at")
        private String createdAt;

        private String description;

        @JsonProperty("employment_type")
        private String employmentType;

        private String industry;
        private String function;
        private String experience;
        private String education;

        @JsonProperty("workplace_type")
        private String workplaceType;
    }
}
