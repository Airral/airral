package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineAnalyticsResponse {
    
    // Application funnel
    private Long applicationsReceived;
    private Long applicationsReviewed;
    private Long applicationsRejected;
    private Long interviewsScheduled;
    private Long interviewsCompleted;
    private Long offersSent;
    private Long offersAccepted;
    private Long hires;
    
    // Conversion rates
    private Double applicationToInterviewRate;
    private Double interviewToOfferRate;
    private Double offerToHireRate;
    
    // Time metrics
    private Double avgTimeToReview; // days
    private Double avgTimeToInterview; // days
    private Double avgTimeToOffer; // days
    private Double avgTimeToHire; // days
    
    // By status breakdown
    private Map<String, Long> applicationsByStatus;
    private Map<String, Long> interviewsByStatus;
    private Map<String, Long> offersByStatus;
    
    // By job breakdown
    private Map<String, Long> applicationsByJob;
    private Map<String, Long> hiresByJob;
}
