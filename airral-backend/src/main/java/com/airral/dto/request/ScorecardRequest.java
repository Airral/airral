package com.airral.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** An interviewer saving their scorecard: as a draft, or submitted for the team. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScorecardRequest {

    @Valid
    @Size(max = 40)
    private List<Rating> ratings;

    @Size(max = 10000, message = "Overall notes must be at most 10,000 characters")
    private String overallNotes;

    @Pattern(regexp = "STRONG_HIRE|HIRE|NO_HIRE|STRONG_NO_HIRE", message = "Choose a recommendation")
    private String recommendation;

    /** Submit for the team. A submitted scorecard can no longer change. */
    private Boolean submit;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rating {
        @NotBlank
        @Size(max = 120)
        private String criterion;

        @Min(value = 1, message = "Ratings are 1 to 5")
        @Max(value = 5, message = "Ratings are 1 to 5")
        private Integer rating;

        @Size(max = 2000, message = "Notes on a criterion must be at most 2,000 characters")
        private String notes;
    }
}
