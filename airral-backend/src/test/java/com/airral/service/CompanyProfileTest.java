package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.UpdateCompanyProfileRequest;
import com.airral.exception.BadRequestException;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyProfileTest {

    private static final long ACME = 1L;

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final InternalJobCatalogProjectionService catalogue = mock(InternalJobCatalogProjectionService.class);
    private final CompanyProfileService service = new CompanyProfileService(organizations, jobs, catalogue);

    private final Organization acme = Organization.builder().id(ACME).name("Acme").domain("acme.test")
            .verificationStatus("VERIFIED").build();

    @BeforeEach
    void setUp() {
        when(organizations.findById(ACME)).thenReturn(Mono.just(acme));
        when(organizations.save(any(Organization.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jobs.findByOrganizationIdAndStatus(ACME, JobStatus.OPEN)).thenReturn(Flux.just(
                Job.builder().id(10L).organizationId(ACME).status(JobStatus.OPEN).build(),
                Job.builder().id(11L).organizationId(ACME).status(JobStatus.OPEN).build()));
        when(catalogue.sync(any(Job.class))).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("a saved profile is trimmed, and the company's open jobs are refreshed to show it")
    void update() {
        UpdateCompanyProfileRequest request = UpdateCompanyProfileRequest.builder()
                .website(" https://acme.test ").logoUrl("https://acme.test/logo.png").industry(" Retail ")
                .companySizeRange("51-200").timezone("America/Chicago").country("United States")
                .about(" We run hardware stores. ").build();

        StepVerifier.create(service.update(ACME, request))
                .assertNext(profile -> {
                    assertThat(profile.getName()).isEqualTo("Acme");
                    assertThat(profile.getWebsite()).isEqualTo("https://acme.test");
                    assertThat(profile.getIndustry()).isEqualTo("Retail");
                    assertThat(profile.getAbout()).isEqualTo("We run hardware stores.");
                    assertThat(profile.getTimezone()).isEqualTo("America/Chicago");
                })
                .verifyComplete();
        // Both open jobs.
        verify(catalogue, times(2)).sync(any(Job.class));
        verify(jobs).findByOrganizationIdAndStatus(ACME, JobStatus.OPEN);
    }

    @Test
    @DisplayName("an unknown time zone is refused before anything is saved")
    void unknownTimeZone() {
        StepVerifier.create(service.update(ACME, UpdateCompanyProfileRequest.builder().timezone("Mars/Olympus").build()))
                .expectError(BadRequestException.class)
                .verify();
        verify(organizations, never()).save(any());
    }

    @Test
    @DisplayName("links must be web addresses, the logo over https, and the size one of the choices")
    void validation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(UpdateCompanyProfileRequest.builder()
                .website("https://acme.test").logoUrl("https://cdn.acme.test/l.png").companySizeRange("1001+").build()))
                .isEmpty();
        assertThat(validator.validate(UpdateCompanyProfileRequest.builder().website("javascript:alert(1)").build()))
                .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("website");
        assertThat(validator.validate(UpdateCompanyProfileRequest.builder().logoUrl("http://acme.test/l.png").build()))
                .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("logoUrl");
        assertThat(validator.validate(UpdateCompanyProfileRequest.builder().companySizeRange("huge").build()))
                .extracting(violation -> violation.getPropertyPath().toString()).containsExactly("companySizeRange");
    }

    @Test
    @DisplayName("a company's jobs say what it says about itself, once it has said something")
    void aboutOnJobs() {
        assertThat(InternalJobCatalogProjectionService.aboutCompany(acme)).isNull();

        acme.setAbout("We run hardware stores.");
        acme.setIndustry("Retail");
        acme.setCompanySizeRange("51-200");
        acme.setWebsite("https://acme.test");
        assertThat(InternalJobCatalogProjectionService.aboutCompany(acme))
                .isEqualTo("We run hardware stores.\nRetail · 51-200 people · https://acme.test");
    }
}
