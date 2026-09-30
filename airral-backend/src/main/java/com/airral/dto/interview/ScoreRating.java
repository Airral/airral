package com.airral.dto.interview;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One criterion on a scorecard, with the interviewer's rating (1 to 5) and notes. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScoreRating {
    private String criterion;
    private String category;
    private Integer weight;
    private Integer rating;
    private String notes;
}
