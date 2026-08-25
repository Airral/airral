package com.airral.dto.schemaorg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaOrgJobPosting {
    @JsonProperty("@type")
    private Object type;
    private String title;
    private String description;
    private Object hiringOrganization;
    private Object jobLocation;
    private Object employmentType;
    private Object datePosted;
    private Object validThrough;
    private String directApply;
    private String url;

    public boolean isJobPosting() {
        if (type == null) {
            return false;
        }
        if (type instanceof String value) {
            return "JobPosting".equalsIgnoreCase(value);
        }
        if (type instanceof List<?> values) {
            return values.stream().anyMatch(value -> value != null && "JobPosting".equalsIgnoreCase(String.valueOf(value)));
        }
        return false;
    }
}
