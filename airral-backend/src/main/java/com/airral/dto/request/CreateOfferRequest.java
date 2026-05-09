package com.airral.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferRequest {

    @NotNull(message = "Application ID is required")
    private Long applicationId;

    @NotNull(message = "Job ID is required")
    private Long jobId;

    @NotNull(message = "Salary is required")
    private BigDecimal salary;

    private String currency;

    private LocalDate startDate;

    private String offerLetter;
    private String benefits;
    private String contingencies;
}
