package com.airral.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("interviews")
public class Interview {

    @Id
    private Long id;

    // Application relationship
    private Long applicationId;

    // Scheduling
    private Long scheduledById;
    private LocalDateTime interviewDate;
    
    // Status: SCHEDULED, COMPLETED, CANCELLED, RESCHEDULED
    private String status;

    // Feedback after interview
    private String feedback;
    private Integer rating; // 1-5 stars
    private String notes;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Helper methods
    public boolean isScheduled() {
        return "SCHEDULED".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isPast() {
        return interviewDate != null && interviewDate.isBefore(LocalDateTime.now());
    }

    public boolean isUpcoming() {
        return "SCHEDULED".equals(status) && 
               interviewDate != null && 
               interviewDate.isAfter(LocalDateTime.now());
    }
}
