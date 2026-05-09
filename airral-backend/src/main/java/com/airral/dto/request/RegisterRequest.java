package com.airral.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).*$", 
             message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit")
    private String password;

    private String firstName;

    private String lastName;

    private String phone;

    // For self-registration (creates new organization)
    private String companyName;
    private String companyDomain;
    private String organizationTier; // QUICK_HIRE, PROFESSIONAL, ENTERPRISE
    private String primaryContactEmail;
    private String primaryContactPhone;

    @Email(message = "Billing email must be valid")
    private String billingEmail;

    // Early setup fields
    private String timezone;
    private String country;
    private String companySizeRange;
    private String industry;

    // Nice-to-have onboarding fields
    private String logoUrl;
    private String brandPrimaryColor;
    private String brandSecondaryColor;
    private List<String> hiringRegions;
    private List<String> offices;
    private List<String> departmentStructure;
    private Map<String, Object> compliancePreferences;
    private Map<String, Object> customSettings;
    private String subscriptionPlanPreference;

    // Optional org limits overrides
    private Integer maxUsers;
    private Integer maxJobs;
    private Integer maxApplicationsPerMonth;

    // For invited users (uses invitation token)
    private String invitationToken;
}
