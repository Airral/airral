package com.airral.dto.interview;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Something interviewers rate a candidate on, and how much it counts (1 to 3). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KitCriterion {

    @NotBlank(message = "A criterion needs a name")
    @Size(max = 120, message = "A criterion must be at most 120 characters")
    private String name;

    @Size(max = 60, message = "A category must be at most 60 characters")
    private String category;

    @Min(value = 1, message = "A criterion's weight is 1 to 3")
    @Max(value = 3, message = "A criterion's weight is 1 to 3")
    private Integer weight;
}
