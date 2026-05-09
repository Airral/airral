package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetricsResponse {
    
    // Job metrics
    private Long totalJobs;
    private Long openJobs;
    private Long closedJobs;
    
    // Application metrics
    private Long totalApplications;
    private Long newApplicationsToday;
    private Long newApplicationsThisWeek;
    private Long newApplicationsThisMonth;
    
    // Interview metrics
    private Long totalInterviews;
    private Long upcomingInterviews;
    private Long completedInterviews;
    private Long interviewsThisWeek;
    
    // Offer metrics
    private Long totalOffers;
    private Long offersAccepted;
    private Long offersDeclined;
    private Long offersPending;
    
    // Hiring metrics
    private Long totalHires;
    private Long hiresThisMonth;
    private Long hiresThisQuarter;
    
    // Referral metrics
    private Long totalReferrals;
    private Long pendingReferrals;
    private Long acceptedReferrals;
    
    // Team metrics
    private Long totalUsers;
    private Long activeUsers;
    private Long totalDepartments;
    
    // Performance metrics
    private Double averageTimeToHire; // in days
    private Double averageTimeToInterview; // in days
    private Double offerAcceptanceRate; // percentage
    private Double applicationToInterviewRate; // percentage
}
