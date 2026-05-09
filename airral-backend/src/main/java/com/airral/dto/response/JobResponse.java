package com.airral.dto.response;

import com.airral.domain.enums.JobStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobResponse {

    private Long id;
    private Long organizationId;
    private String title;
    private String description;
    private Long departmentId;
    private String department;
    private String location;
    private String employmentType;
    
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String currency;
    
    private String requirements;
    private String niceToHave;
    
    private JobStatus status;
    
    private List<String> atsKeywords;
    private String atsWeights;
    private Integer atsMinScore;
    
    private Boolean linkedInEnabled;
    private String linkedinPostId;
    
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
