package com.airral.service;

import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.JobStatus;
import com.airral.exception.NotFoundException;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Closing and reopening a job changes its status and nothing else. Runs the
 * real JobService over mocked repositories.
 */
class JobStatusChangeTest {

    private static final long ACME = 1L;

    private final JobRepository jobs = mock(JobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InternalJobCatalogProjectionService catalogue = mock(InternalJobCatalogProjectionService.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private JobService service;
    private Job job;

    @BeforeEach
    void setUp() {
        service = new JobService(jobs, users, organizations,
                mock(ExternalJobPostingStore.class), catalogue, mock(com.airral.repository.DepartmentRepository.class), mock(com.airral.repository.InterviewKitRepository.class));
        job = Job.builder().id(10L).organizationId(ACME).createdById(7L).title("Backend engineer")
                .description("Build the API").location("Remote").employmentType("Full-time")
                .salaryMin(new BigDecimal("150000")).salaryMax(new BigDecimal("190000")).currency("USD")
                .requirements("Java, Postgres").atsKeywords(new String[] {"java", "postgres"}).atsMinScore(60)
                .status(JobStatus.OPEN).build();
        when(jobs.findByIdAndOrganizationId(10L, ACME)).thenReturn(Mono.just(job));
        when(jobs.save(any(Job.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(catalogue.sync(any(Job.class))).thenReturn(Mono.empty());
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        when(users.findById(7L)).thenReturn(Mono.just(User.builder().id(7L).firstName("Amy").lastName("Adams").build()));
    }

    @Test
    @DisplayName("closing a job keeps its location, pay, requirements and keywords")
    void closingKeepsTheDetails() {
        StepVerifier.create(service.updateJobStatus(10L, JobStatus.CLOSED, ACME))
                .assertNext(response -> assertThat(response.getStatus()).isEqualTo(JobStatus.CLOSED))
                .verifyComplete();

        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getLocation()).isEqualTo("Remote");
        assertThat(saved.getValue().getSalaryMin()).isEqualByComparingTo("150000");
        assertThat(saved.getValue().getRequirements()).isEqualTo("Java, Postgres");
        assertThat(saved.getValue().getAtsKeywords()).containsExactly("java", "postgres");
        assertThat(saved.getValue().getAtsMinScore()).isEqualTo(60);
        verify(catalogue).sync(saved.getValue());
    }

    @Test
    @DisplayName("a company cannot change another company's job")
    void otherCompanysJob() {
        when(jobs.findByIdAndOrganizationId(11L, ACME)).thenReturn(Mono.empty());

        StepVerifier.create(service.updateJobStatus(11L, JobStatus.CLOSED, ACME))
                .expectError(NotFoundException.class)
                .verify();
        verify(jobs, never()).save(any());
    }
}
