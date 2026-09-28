package com.airral.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a company says about itself. The name and email domain are not here:
 * AIRRAL reviewed the company under them, so they change through AIRRAL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCompanyProfileRequest {

    @Size(max = 255, message = "The website must be at most 255 characters")
    @Pattern(regexp = "^(https?://\\S+)?$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "The website must start with https:// or http://")
    private String website;

    @Size(max = 1000, message = "The logo link must be at most 1,000 characters")
    @Pattern(regexp = "^(https://\\S+)?$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "The logo must be an https:// link to an image")
    private String logoUrl;

    @Size(max = 100, message = "The industry must be at most 100 characters")
    private String industry;

    @Pattern(regexp = "^(1-10|11-50|51-200|201-500|501-1000|1001\\+)?$", message = "Choose a company size")
    private String companySizeRange;

    @Size(max = 64)
    private String timezone;

    @Size(max = 100, message = "The country must be at most 100 characters")
    private String country;

    @Size(max = 2000, message = "About the company must be at most 2,000 characters")
    private String about;
}
