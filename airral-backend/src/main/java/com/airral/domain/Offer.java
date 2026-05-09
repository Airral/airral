package com.airral.domain;

import com.airral.domain.enums.OfferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("offers")
public class Offer {

    @Id
    private Long id;

    // Application and job relationship
    private Long applicationId;
    private Long jobId;

    // Offer details
    private BigDecimal salary;
    private String currency;
    private LocalDate startDate;

    // Offer documents
    private String offerLetter;
    private String benefits;
    private String contingencies;

    // Status
    private OfferStatus status;

    // Important dates
    private LocalDateTime sentAt;
    private LocalDateTime expiresAt;
    private LocalDateTime respondedAt;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isDraft() {
        return status == OfferStatus.DRAFT;
    }

    public boolean isSent() {
        return status == OfferStatus.SENT;
    }

    public boolean isAccepted() {
        return status == OfferStatus.ACCEPTED;
    }

    public boolean isExpired() {
        return status == OfferStatus.EXPIRED || 
               (expiresAt != null && expiresAt.isBefore(LocalDateTime.now()));
    }
}
