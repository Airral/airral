package com.airral.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyProfileResponse {
    private Long id;
    private String name;
    private String domain;
    private String website;
    private String logoUrl;
    private String industry;
    private String companySizeRange;
    private String timezone;
    private String country;
    private String about;
    /** PENDING, VERIFIED or REJECTED. */
    private String verificationStatus;
}
