package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.UpdateCompanyProfileRequest;
import com.airral.dto.response.CompanyProfileResponse;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * A company's profile: what it says about itself on its public jobs.
 */
@Service
public class CompanyProfileService {

    private final OrganizationRepository organizationRepository;
    private final JobRepository jobRepository;
    private final InternalJobCatalogProjectionService catalogue;

    public CompanyProfileService(OrganizationRepository organizationRepository,
                                 JobRepository jobRepository,
                                 InternalJobCatalogProjectionService catalogue) {
        this.organizationRepository = organizationRepository;
        this.jobRepository = jobRepository;
        this.catalogue = catalogue;
    }

    public Mono<CompanyProfileResponse> get(Long organizationId) {
        return company(organizationId).map(CompanyProfileService::toResponse);
    }

    /**
     * Save the profile, then refresh the company's open jobs on
     * apply.airral.com so they show it.
     */
    public Mono<CompanyProfileResponse> update(Long organizationId, UpdateCompanyProfileRequest request) {
        String timezone = trimmed(request.getTimezone());
        if (timezone != null) {
            try {
                timezone = ZoneId.of(timezone).getId();
            } catch (DateTimeException e) {
                return Mono.error(new BadRequestException("Unknown time zone: " + request.getTimezone()));
            }
        }
        String zone = timezone;
        return company(organizationId)
                .flatMap(company -> {
                    company.setWebsite(trimmed(request.getWebsite()));
                    company.setLogoUrl(trimmed(request.getLogoUrl()));
                    company.setIndustry(trimmed(request.getIndustry()));
                    company.setCompanySizeRange(trimmed(request.getCompanySizeRange()));
                    company.setTimezone(zone);
                    company.setCountry(trimmed(request.getCountry()));
                    company.setAbout(trimmed(request.getAbout()));
                    company.setUpdatedAt(LocalDateTime.now());
                    return organizationRepository.save(company);
                })
                .flatMap(saved -> jobRepository.findByOrganizationIdAndStatus(organizationId, JobStatus.OPEN)
                        .concatMap(catalogue::sync)
                        .then(Mono.just(saved)))
                .map(CompanyProfileService::toResponse);
    }

    private Mono<Organization> company(Long organizationId) {
        if (organizationId == null) return Mono.error(new NotFoundException("Company not found"));
        return organizationRepository.findById(organizationId)
                .switchIfEmpty(Mono.error(new NotFoundException("Company not found")));
    }

    static CompanyProfileResponse toResponse(Organization company) {
        return CompanyProfileResponse.builder()
                .id(company.getId())
                .name(company.getName())
                .domain(company.getDomain())
                .website(company.getWebsite())
                .logoUrl(company.getLogoUrl())
                .industry(company.getIndustry())
                .companySizeRange(company.getCompanySizeRange())
                .timezone(company.getTimezone())
                .country(company.getCountry())
                .about(company.getAbout())
                .verificationStatus(company.getVerificationStatus())
                .build();
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
