package com.airral.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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

    /** Ignored: an offer is always for the application's own job. Kept so older clients still validate. */
    private Long jobId;

    @NotNull(message = "Salary is required")
    @Positive(message = "Salary must be more than zero")
    @Digits(integer = 10, fraction = 2, message = "Salary must be at most 10 digits, with 2 decimals")
    private BigDecimal salary;

    @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter code, like USD")
    private String currency;

    private LocalDate startDate;

    @Size(max = 20000, message = "The offer letter must be at most 20,000 characters")
    private String offerLetter;

    @Size(max = 5000, message = "Benefits must be at most 5,000 characters")
    private String benefits;

    @Size(max = 5000, message = "Contingencies must be at most 5,000 characters")
    private String contingencies;
}
