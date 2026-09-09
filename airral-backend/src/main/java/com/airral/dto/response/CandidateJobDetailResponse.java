package com.airral.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CandidateJobDetailResponse {
    private String jobId;
    private String sourceType;
    private String sourceName;
    private String sourceBoardToken;
    private String externalJobId;
    private String externalInternalJobId;

    private String title;
    private String companyName;
    private String companyDomain;
    private String companyLogoUrl;
    private String department;
    private String location;
    private String workMode;
    private String employmentType;
    private String descriptionHtml;
    private String descriptionText;
    private String descriptionExcerpt;

    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private String salaryCurrency;

    /**
     * The interval salaryMin/salaryMax are quoted in -- YEAR, HOUR, MONTH, WEEK,
     * DAY, ONE_TIME -- or null when the source never said. Without it the bare
     * numbers are unreadable: an hourly $50 and an annual $50,000 are the same
     * column, and consumers that assumed YEAR published a $50/year job.
     */
    private String salaryPeriod;
    private String salaryLabel;

    private String applyUrl;
    private String jobUrl;
    private String applyMode;
    private OffsetDateTime sourceUpdatedAt;
    private String postedLabel;

    private Integer matchScore;
    private List<String> matchReasons;
    private Integer connectionsCount;
    private List<String> tags;
    private String sourcePayloadHash;

    private Integer jobQualityScore;
    private List<String> qualityReasons;
    private String totalCompLabel;
    private String compensationConfidence;

    private String sponsorshipLanguage;
    private Integer visaConfidenceScore;
    private List<String> visaReasons;
    private Boolean requiresUsWorkAuthorization;
    private Boolean contractOrStaffingRisk;
    private Boolean stemOptRisk;
    private Boolean h1bTransferFit;
    private Boolean capExemptFit;

    /** Inferred experience level label: "Entry", "Mid", "Senior", "Staff+", "Lead", "Director+" */
    private String seniorityLabel;
    /** Minimum years of experience extracted from title or description (null if unknown) */
    private Integer experienceYears;
}
