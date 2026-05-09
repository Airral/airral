package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("referrals")
public class Referral {

    @Id
    private Long id;

    private Long organizationId;
    private Long referredById; // Employee/Manager who referred
    
    // Referred person details
    private String referredName;
    private String referredEmail;
    private String referredPhone;
    private Long jobId;
    private String notes;
    private String relationship;
    private String resumeUrl;

    // Status: PENDING, APPROVED, REJECTED, HIRED
    private String status;

    // Review tracking
    private LocalDateTime reviewedAt;
    private Long reviewedById;
    private Long applicationId; // Created when approved

    // Bonus tracking
    private BigDecimal bonusAmount;
    private LocalDateTime bonusPaidAt;

    // Timestamps
    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    public boolean isRejected() {
        return "REJECTED".equals(status);
    }

    public boolean hasApplication() {
        return applicationId != null;
    }
}
