package com.airral.dto.response;

import com.airral.domain.enums.ApplicantStage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One of an applicant's own applications, as the applicant sees it.
 *
 * <p>This is deliberately not {@link ApplicationResponse}. That one is the
 * company's view: it names the teammate who reviewed the application and
 * carries the company's screening score and keyword list, and none of that is
 * the applicant's to see.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyApplicationResponse {

    private Long id;
    private Long jobId;
    private String jobTitle;
    private String companyName;
    private ApplicantStage stage;
    private LocalDateTime appliedAt;
    private LocalDateTime updatedAt;
}
