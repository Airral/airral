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

        /**
         * "Full time" / "Part time", as the board reports it.
         *
         * <p>Returned on the list call and previously not declared here, so it was
         * discarded during deserialization -- which is why employment_type was
         * null on every Workday posting, and Workday is the bulk of the
         * catalogue. Costs nothing to keep: no extra request, no parsing.
         */
        private String timeType;
        private List<String> bulletFields;
    }
}
