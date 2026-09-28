package com.airral.service;

import com.airral.controller.ApplicationController;
import com.airral.domain.Application;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may create an application, and under whose name.
 *
 * <p>Runs the real ApplicationService over mocked repositories, so the rules
 * under test are the ones production runs. Every request below names someone
 * else (applicant 99, someone-else@example.com), because a request is exactly
 * what a caller can forge.
 */
class ApplicationCreationRulesTest {

    private static final long OUR_COMPANY = 1L;
    private static final long OTHER_COMPANY = 2L;
    private static final long JOB = 10L;

    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationService(applications, jobs, mock(UserRepository.class), organizations);
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private void jobIs(JobStatus status, long companyId, String companyStatus) {
        when(jobs.findById(JOB)).thenReturn(Mono.just(
                Job.builder().id(JOB).title("Backend engineer").organizationId(companyId).status(status).build()));
        when(organizations.findById(companyId)).thenReturn(Mono.just(
                Organization.builder().id(companyId).isActive(true).verificationStatus(companyStatus).build()));
    }

    private static SubmitApplicationRequest request() {
        return SubmitApplicationRequest.builder()
                .jobId(JOB)
                .applicantId(99L)
                .applicantName("Amy Adams")
                .applicantEmail("someone-else@example.com")
                .resumeUrl("resume-7.pdf")
                .build();
    }

    private Application saved() {
        ArgumentCaptor<Application> captor = ArgumentCaptor.forClass(Application.class);
        verify(applications).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("an applicant's application is filed under their own account and address")
    void applicantAppliesAsThemselves() {
        jobIs(JobStatus.OPEN, OUR_COMPANY, CompanyVerificationService.VERIFIED);

        StepVerifier.create(service.applyAsApplicant(request(), 7L, "amy@example.com"))
                .assertNext(response -> assertThat(response.getApplicantId()).isEqualTo(7L))
                .verifyComplete();

        Application saved = saved();
        assertThat(saved.getApplicantId()).isEqualTo(7L);
        assertThat(saved.getApplicantEmail()).isEqualTo("amy@example.com");
    }

    @Test
    @DisplayName("an applicant cannot apply to a job that is not open")
    void applicantCannotApplyToUnopenedJob() {
        jobIs(JobStatus.DRAFT, OUR_COMPANY, CompanyVerificationService.VERIFIED);

        StepVerifier.create(service.applyAsApplicant(request(), 7L, "amy@example.com"))
                .expectError(NotFoundException.class)
                .verify();
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("an applicant cannot apply to a job at a company AIRRAL has not verified")
    void applicantCannotApplyToUnverifiedCompany() {
        jobIs(JobStatus.OPEN, OUR_COMPANY, "PENDING");

        StepVerifier.create(service.applyAsApplicant(request(), 7L, "amy@example.com"))
                .expectError(NotFoundException.class)
                .verify();
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("HR adds a candidate to its own company's job, linked to no account")
    void hrAddsCandidateToOwnJob() {
        // A pending company still runs its own pipeline; only candidates wait
        // for verification.
        jobIs(JobStatus.OPEN, OUR_COMPANY, "PENDING");

        StepVerifier.create(service.addCandidate(request(), OUR_COMPANY))
                .expectNextCount(1)
                .verifyComplete();

        Application saved = saved();
        assertThat(saved.getApplicantId()).isNull();
        assertThat(saved.getApplicantEmail()).isEqualTo("someone-else@example.com");
    }

    @Test
    @DisplayName("HR cannot add a candidate to another company's job")
    void hrCannotAddToAnotherCompanysJob() {
        jobIs(JobStatus.OPEN, OTHER_COMPANY, CompanyVerificationService.VERIFIED);

        StepVerifier.create(service.addCandidate(request(), OUR_COMPANY))
                .expectError(NotFoundException.class)
                .verify();
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("the session decides whose application it is, not the request")
    void controllerUsesTheSessionIdentity() {
        ApplicationService stub = mock(ApplicationService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRoleFromToken("tok")).thenReturn("APPLICANT");
        when(jwt.getUserIdFromToken("tok")).thenReturn(7L);
        when(jwt.getEmailFromToken("tok")).thenReturn("amy@example.com");
        when(stub.applyAsApplicant(any(), any(), any())).thenReturn(Mono.empty());

        new ApplicationController(stub, jwt).submitApplication(request(), "Bearer tok").block();

        verify(stub).applyAsApplicant(any(), eq(7L), eq("amy@example.com"));
    }

    @Test
    @DisplayName("an employee cannot create an application")
    void employeeIsTurnedAway() {
        ApplicationService stub = mock(ApplicationService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRoleFromToken("tok")).thenReturn("EMPLOYEE");

        StepVerifier.create(new ApplicationController(stub, jwt).submitApplication(request(), "Bearer tok"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();
        verifyNoInteractions(stub);
    }
}
