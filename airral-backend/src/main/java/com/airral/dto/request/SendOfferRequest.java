package com.airral.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendOfferRequest {

    /** How long the candidate has to answer. 7 days when not given. */
    @Min(value = 1, message = "An offer is open for 1 to 60 days")
    @Max(value = 60, message = "An offer is open for 1 to 60 days")
    private Integer expiresInDays;
}
