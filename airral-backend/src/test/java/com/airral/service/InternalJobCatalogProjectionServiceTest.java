package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.response.CandidateJobDetailResponse;
import com.airral.dto.response.CandidateJobSummaryResponse;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalJobCatalogProjectionServiceTest {

    private JobRepository jobRepository;
    private OrganizationRepository organizationRepository;
    private ExternalJobPostingStore externalJobPostingStore;
    private InternalJobCatalogProjectionService service;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        organizationRepository = mock(OrganizationRepository.class);
        externalJobPostingStore = mock(ExternalJobPostingStore.class);
        service = new InternalJobCatalogProjectionService(
                jobRepository,
                organizationRepository,
                externalJobPostingStore);
    }

    @Test
    void publishesOpenEmployerJobIntoCachedApplicantCatalog() {
        Organization organization = Organization.builder()
                .id(7L)
                .name("Acme Health")
                .domain("acme.example")
                .logoUrl("https://acme.example/logo.png")
                .build();
        Job job = openJob();
        ExternalJobSourceRecord source = new ExternalJobSourceRecord(
                31L,
                41L,
                organization.getName(),
                organization.getDomain(),
                InternalJobCatalogProjectionService.SOURCE_TYPE,
                "organization-7",
                "AIRRAL employer");

        when(organizationRepository.findById(7L)).thenReturn(Mono.just(organization));
        when(externalJobPostingStore.ensureInternalSource(organization)).thenReturn(Mono.just(source));
        when(externalJobPostingStore.upsertJob(any(), any(), anyInt())).thenReturn(Mono.just(1L));
        when(externalJobPostingStore.cacheJobDetail(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.sync(job)).verifyComplete();

        ArgumentCaptor<CandidateJobSummaryResponse> summaryCaptor =
                ArgumentCaptor.forClass(CandidateJobSummaryResponse.class);
        ArgumentCaptor<CandidateJobDetailResponse> detailCaptor =
                ArgumentCaptor.forClass(CandidateJobDetailResponse.class);
        verify(externalJobPostingStore).upsertJob(any(), summaryCaptor.capture(), anyInt());
        verify(externalJobPostingStore).cacheJobDetail(detailCaptor.capture());

        CandidateJobSummaryResponse summary = summaryCaptor.getValue();
        assertThat(summary.getJobId()).isEqualTo("airral_internal:organization-7:19");
        assertThat(summary.getSourceType()).isEqualTo("AIRRAL_INTERNAL");
        assertThat(summary.getApplyMode()).isEqualTo("INTERNAL_APPLY");
        assertThat(summary.getCompanyName()).isEqualTo("Acme Health");
        assertThat(summary.getWorkMode()).isEqualTo("REMOTE");
        assertThat(summary.getSalaryLabel()).isEqualTo("$110,000 - $145,000");
        assertThat(summary.getQualityReasons()).contains("Posted directly by employer");

        CandidateJobDetailResponse detail = detailCaptor.getValue();
        assertThat(detail.getExternalInternalJobId()).isEqualTo("19");
        assertThat(detail.getDescriptionText())
                .contains("Build reliable care workflows.")
                .contains("Requirements\nJava, Spring Boot, PostgreSQL")
                .contains("Nice to have\nHealthcare experience");
        assertThat(detail.getSalaryMin()).isEqualByComparingTo("110000");
        assertThat(detail.getSalaryCurrency()).isEqualTo("USD");
        assertThat(detail.getSourcePayloadHash()).hasSize(64);
    }

    @Test
    void deactivatesClosedJobWithoutPublishingIt() {
        Job job = openJob();
        job.setStatus(JobStatus.CLOSED);
        when(externalJobPostingStore.deactivateInternalJob(19L)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.sync(job)).verifyComplete();

        verify(externalJobPostingStore).deactivateInternalJob(19L);
        verify(organizationRepository, never()).findById(anyLong());
        verify(externalJobPostingStore, never()).ensureInternalSource(any());
        verify(externalJobPostingStore, never()).upsertJob(any(), any(), anyInt());
    }

    private Job openJob() {
        return Job.builder()
                .id(19L)
                .organizationId(7L)
                .title("Senior Backend Engineer")
                .description("Build reliable care workflows.")
                .department("Engineering")
                .location("Remote - US")
                .employmentType("Full-time")
                .salaryMin(new BigDecimal("110000"))
                .salaryMax(new BigDecimal("145000"))
                .currency("USD")
                .requirements("Java, Spring Boot, PostgreSQL")
                .niceToHave("Healthcare experience")
                .status(JobStatus.OPEN)
                .createdAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 17, 9, 30))
                .build();
    }
}
