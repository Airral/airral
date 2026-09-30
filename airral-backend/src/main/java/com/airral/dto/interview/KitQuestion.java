package com.airral.dto.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A question an interview kit suggests asking. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KitQuestion {

    @NotBlank(message = "A question needs its text")
    @Size(max = 500, message = "A question must be at most 500 characters")
    private String text;

    @Size(max = 60, message = "A category must be at most 60 characters")
    private String category;
}
