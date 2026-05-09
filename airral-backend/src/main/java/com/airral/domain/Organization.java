package com.airral.domain;

import com.airral.domain.enums.OrganizationTier;
import com.airral.domain.enums.SubscriptionStatus;
import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("organizations")
public class Organization {

    @Id
    private Long id;

    private String name;
    private String domain;

    // Subscription & Tier
    private OrganizationTier tier;
    private SubscriptionStatus subscriptionStatus;
    private LocalDate subscriptionStartDate;
    private LocalDate subscriptionEndDate;
    private BigDecimal monthlyPrice;

    // Limits based on tier
    private Integer maxUsers;
    private Integer maxJobs;
    private Integer maxApplicationsPerMonth;

    // Contact information
    private String primaryContactEmail;
    private String primaryContactPhone;
    private String billingEmail;

    // Onboarding/profile fields
    private String timezone;
    private String country;
    private String companySizeRange;
    private String industry;
    private String logoUrl;
    private String brandPrimaryColor;
    private String brandSecondaryColor;
    private String subscriptionPlanPreference;
    private List<String> hiringRegions;
    private List<String> offices;
    private List<String> departmentStructure;
    private Json compliancePreferences;
    private Json customSettings;

    // Settings (stored as JSONB in DB)
    private Json settings;

    // Status
    private Boolean isActive;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdById;
}
