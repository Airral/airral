package com.airral.dto.request;

import com.airral.dto.interview.KitCriterion;
import com.airral.dto.interview.KitQuestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewKitRequest {

    @NotBlank(message = "Give the kit a name")
    @Size(max = 120, message = "A kit's name must be at most 120 characters")
    private String name;

    @Size(max = 2000, message = "A kit's description must be at most 2,000 characters")
    private String description;

    @Min(value = 15, message = "An interview is at least 15 minutes")
    @Max(value = 480, message = "An interview is at most 8 hours")
    private Integer durationMinutes;

    @Valid
    @Size(max = 40, message = "A kit can have at most 40 questions")
    private List<KitQuestion> questions;

    @Valid
    @Size(max = 20, message = "A kit can have at most 20 criteria")
    private List<KitCriterion> criteria;
}
