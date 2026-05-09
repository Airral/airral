package com.airral.dto.response;

import com.airral.domain.enums.OfferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferResponse {

    private Long id;
    private Long applicationId;
    private Long jobId;
    
    // Candidate info
    private String candidateName;
    private String candidateEmail;
    private String jobTitle;
    
    // Offer details
    private BigDecimal salary;
    private String currency;
    private LocalDate startDate;
    
    private String offerLetter;
    private String benefits;
    private String contingencies;
    
    private OfferStatus status;
    
    private LocalDateTime sentAt;
    private LocalDateTime expiresAt;
    private LocalDateTime respondedAt;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
