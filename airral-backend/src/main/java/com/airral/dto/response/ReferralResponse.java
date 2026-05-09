package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralResponse {

    private Long id;
    private Long organizationId;
    
    // Who referred
    private Long referredById;
    private String referredByName;
    
    // Referred person
    private String referredName;
    private String referredEmail;
    private String referredPhone;
    
    private Long jobId;
    private String jobTitle;
    private String notes;
    private String relationship;
    private String resumeUrl;
    
    private String status;
    
    private LocalDateTime reviewedAt;
    private String reviewedBy;
    private Long applicationId;
    
    private BigDecimal bonusAmount;
    private LocalDateTime bonusPaidAt;
    
    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;
}
