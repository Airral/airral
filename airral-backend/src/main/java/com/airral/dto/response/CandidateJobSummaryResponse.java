package com.airral.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CandidateJobSummaryResponse {
    private String jobId;
    private String sourceType;
    private String sourceName;
    private String sourceBoardToken;
    private String externalJobId;

    private String title;
    private String companyName;
    private String companyDomain;
    private String companyLogoUrl;
    private String department;
    private String location;
    private String workMode;
    private String employmentType;

    private String salaryLabel;

    /**
     * Interval salaryLabel is quoted in, or null when the source never stated one.
     * Carried on the summary because the sync writes from this object: without it
     * the column is only ever populated by a detail view, so a posting nobody
     * opened would keep a correct "$40/hr" label beside a null period, and the
     * job page would omit structured pay for the whole catalogue.
     */
    private String salaryPeriod;
    private String applyUrl;
    private String jobUrl;
    private String applyMode;
    private Boolean easyApplyAvailable;

    private OffsetDateTime sourceUpdatedAt;
    private String postedLabel;
    private Integer matchScore;
    private List<String> matchReasons;
    private Integer connectionsCount;
    private List<String> tags;

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

    /**
     * The posting body, when the source hands it over on the list call.
     *
     * <p>Carried so the sync can derive the same signals as a detail view. Every
     * text-derived column -- salary, sponsorship, experience, the search vector --
     * was previously computed against a null description on the write path, because
     * this field did not exist and the summary overload of withDecisionSignals had
     * nothing to pass. The result was a default written over a real value on every
     * run.
     *
     * <p>Not serialized. This is a derivation input, not part of the list contract:
     * a list page of 100 rows would otherwise carry roughly a megabyte of prose the
     * client never renders. Read paths leave it null and use the stored columns.
     */
    @JsonIgnore
    private String descriptionText;
}
