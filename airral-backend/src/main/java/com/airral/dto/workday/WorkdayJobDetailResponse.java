package com.airral.dto.workday;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkdayJobDetailResponse {
    private WorkdayJobPostingInfo jobPostingInfo;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkdayJobPostingInfo {
        private String id;
        private String title;
        private String jobDescription;
        private String location;
        private String postedOn;
        private String startDate;
        private String timeType;
        private String jobReqId;
        private String jobPostingId;
        private String jobPostingSiteId;
        private WorkdayDescriptor country;
        private Boolean canApply;
        private Boolean posted;
        private String remoteType;
        private String externalUrl;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkdayDescriptor {
        private String descriptor;
        private String id;
        private String alpha2Code;
    }
}
