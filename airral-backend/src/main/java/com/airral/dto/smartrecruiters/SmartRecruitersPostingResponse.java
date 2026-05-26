package com.airral.dto.smartrecruiters;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SmartRecruitersPostingResponse {
    private Integer offset;
    private Integer limit;
    private Integer totalFound;
    private List<Posting> content;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Posting {
        private String id;
        private String uuid;
        private String jobId;
        private String jobAdId;
        private String name;
        private String refNumber;
        private Company company;
        private OffsetDateTime releasedDate;
        private Location location;
        private Label industry;
        private Label department;
        private Label function;
        private Label typeOfEmployment;
        private Label experienceLevel;
        private String postingUrl;
        private String applyUrl;
        private String referralUrl;
        private JobAd jobAd;
        private Boolean active;
        private String visibility;
        private Language language;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Company {
        private String identifier;
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Location {
        private String city;
        private String region;
        private String country;
        private Boolean remote;
        private Boolean hybrid;
        private String hybridDescription;
        private String fullLocation;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Label {
        private String id;
        private String label;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Language {
        private String code;
        private String label;
        private String labelNative;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobAd {
        private JobAdSections sections;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobAdSections {
        private JobAdSection companyDescription;
        private JobAdSection jobDescription;
        private JobAdSection qualifications;
        private JobAdSection additionalInformation;
        private Map<String, JobAdSection> customSections;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobAdSection {
        private String title;
        private String text;
    }
}
