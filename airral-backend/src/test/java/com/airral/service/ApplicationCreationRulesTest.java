package com.airral.service;

import com.airral.controller.ApplicationController;
import com.airral.domain.Application;
import com.airral.domain.CandidateProfile;
import com.airral.domain.CandidateResumeDocument;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.enums.JobStatus;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.CandidateResumeDocumentRepository;
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
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final CandidateUpdateEmails emails = mock(CandidateUpdateEmails.class);
    private final CandidateResumeDocumentRepository resumes = mock(CandidateResumeDocumentRepository.class);
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationService(applications, jobs, mock(UserRepository.class), organizations, profiles, emails, resumes);
        // Amy's resume on file, as parsed when she uploaded it.
        when(resumes.findByIdAndUserId(70L, 7L)).thenReturn(Mono.just(CandidateResumeDocument.builder().id(70L).userId(7L)
                .extractedText("Six years running retail stores: inventory management, scheduling, Excel.").build()));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        // Amy, applicant 7, has a resume on file and has not applied yet.
        when(profiles.findByUserId(7L)).thenReturn(Mono.just(CandidateProfile.builder().userId(7L).activeResumeDocumentId(70L).build()));
        when(applications.existsByJobIdAndApplicantId(JOB, 7L)).thenReturn(Mono.just(false));
        when(applications.existsByJobIdAndApplicantEmail(eq(JOB), any())).thenReturn(Mono.just(false));
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
        // The resume on file is attached, and a link the request names is not.
        assertThat(saved.getResumeDocumentId()).isEqualTo(70L);
        assertThat(saved.getResumeUrl()).isNull();
        // And the email saying the company has it goes to Amy's own address.
        ArgumentCaptor<Application> emailed = ArgumentCaptor.forClass(Application.class);
        verify(emails).applicationReceived(emailed.capture());
        assertThat(emailed.getValue().getApplicantEmail()).isEqualTo("amy@example.com");
        assertThat(emailed.getValue().getJobId()).isEqualTo(JOB);
    }

    @Test
    @DisplayName("the job's keywords are read against the resume she attached, not only a cover letter")
    void alignmentReadsTheResume() {
        when(jobs.findById(JOB)).thenReturn(Mono.just(Job.builder().id(JOB).title("Store manager")
                .organizationId(OUR_COMPANY).status(JobStatus.OPEN)
                .atsKeywords(new String[] {"Inventory Management", "Excel", "Forklift"}).build()));
        when(organizations.findById(OUR_COMPANY)).thenReturn(Mono.just(Organization.builder().id(OUR_COMPANY)
                .isActive(true).verificationStatus(CompanyVerificationService.VERIFIED).build()));

        service.applyAsApplicant(request(), 7L, "amy@example.com").block();

        Application saved = saved();
        assertThat(saved.getAtsMatchedKeywords()).containsExactly("Inventory Management", "Excel");
        assertThat(saved.getAtsMissingKeywords()).containsExactly("Forklift");
        assertThat(saved.getAtsScore()).isEqualTo(66);
        assertThat(saved.getAlignmentSource()).isEqualTo("RESUME_AND_NOTE");
    }

    @Test
    @DisplayName("a resume whose text could not be read is recorded as unreadable, and only the note is checked")
    void unreadableResume() {
        when(jobs.findById(JOB)).thenReturn(Mono.just(Job.builder().id(JOB).title("Store manager")
                .organizationId(OUR_COMPANY).status(JobStatus.OPEN)
                .atsKeywords(new String[] {"Inventory Management", "Excel", "Forklift"}).build()));
        when(organizations.findById(OUR_COMPANY)).thenReturn(Mono.just(Organization.builder().id(OUR_COMPANY)
                .isActive(true).verificationStatus(CompanyVerificationService.VERIFIED).build()));
        // A scanned resume: the parser kept no text and found no skills.
        when(resumes.findByIdAndUserId(70L, 7L)).thenReturn(Mono.just(CandidateResumeDocument.builder().id(70L).userId(7L)
                .parseStatus("PARSE_FAILED").parsedSkills(io.r2dbc.postgresql.codec.Json.of("[]")).build()));
        SubmitApplicationRequest withNote = request();
        withNote.setCoverLetter("I have driven a forklift for years.");

        service.applyAsApplicant(withNote, 7L, "amy@example.com").block();

        Application saved = saved();
        assertThat(saved.getAlignmentSource()).isEqualTo("UNREADABLE_RESUME");
        assertThat(saved.getAtsMatchedKeywords()).containsExactly("Forklift");
        assertThat(saved.getAtsMissingKeywords()).containsExactly("Inventory Management", "Excel");
    }

    @Test
    @DisplayName("an applicant applies to a job once")
    void applicantAppliesOnce() {
        jobIs(JobStatus.OPEN, OUR_COMPANY, CompanyVerificationService.VERIFIED);
        when(applications.existsByJobIdAndApplicantId(JOB, 7L)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyAsApplicant(request(), 7L, "amy@example.com"))
                .expectError(ConflictException.class)
                .verify();
        verify(applications, never()).save(any());
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("an applicant without a resume on file is asked to upload one")
    void applicantNeedsAResume() {
        jobIs(JobStatus.OPEN, OUR_COMPANY, CompanyVerificationService.VERIFIED);
        when(profiles.findByUserId(7L)).thenReturn(Mono.just(CandidateProfile.builder().userId(7L).build()));

        StepVerifier.create(service.applyAsApplicant(request(), 7L, "amy@example.com"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(BadRequestException.class)
                        .hasMessageContaining("resume"))
                .verify();
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("HR opens an attached resume only for its own company's jobs in scope")
    void resumeFollowsTheApplication() {
        Application attached = Application.builder().id(55L).jobId(JOB).applicantId(7L).resumeDocumentId(70L).build();
        when(applications.findByIdAndOrganizationId(55L, OUR_COMPANY)).thenReturn(Mono.just(attached));
        Application typedLink = Application.builder().id(56L).jobId(JOB).resumeUrl("https://example.com/cv.pdf").build();
        when(applications.findByIdAndOrganizationId(56L, OUR_COMPANY)).thenReturn(Mono.just(typedLink));

        StepVerifier.create(service.applicationWithResume(55L, OUR_COMPANY, JobScope.wholeCompany()))
                .assertNext(found -> assertThat(found.getResumeDocumentId()).isEqualTo(70L))
                .verifyComplete();
        StepVerifier.create(service.applicationWithResume(55L, OUR_COMPANY, JobScope.only(java.util.Set.of(99L))))
                .expectError(NotFoundException.class).verify();
        StepVerifier.create(service.applicationWithResume(56L, OUR_COMPANY, JobScope.wholeCompany()))
                .expectError(NotFoundException.class).verify();
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

        SubmitApplicationRequest typed = request();
        typed.setApplicantEmail(" Someone-Else@Example.com ");
        typed.setResumeUrl(" ");

        StepVerifier.create(service.addCandidate(typed, OUR_COMPANY))
                .expectNextCount(1)
                .verifyComplete();

        Application saved = saved();
        assertThat(saved.getApplicantId()).isNull();
        assertThat(saved.getApplicantEmail()).isEqualTo("someone-else@example.com");
        assertThat(saved.getResumeUrl()).isNull();
        // Added with a link, not a document: only a note could be read.
        assertThat(saved.getAlignmentSource()).isEqualTo("NOTE");
        // They did not apply, so nothing tells them they did.
        verifyNoInteractions(emails);
    }

    @Test
    @DisplayName("HR cannot add the same person to a job twice")
    void hrAddsACandidateOnce() {
        jobIs(JobStatus.OPEN, OUR_COMPANY, CompanyVerificationService.VERIFIED);
        when(applications.existsByJobIdAndApplicantEmail(JOB, "someone-else@example.com")).thenReturn(Mono.just(true));

        StepVerifier.create(service.addCandidate(request(), OUR_COMPANY))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ConflictException.class)
                        .hasMessageContaining("already a candidate"))
                .verify();
        verify(applications, never()).save(any());
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

        new ApplicationController(stub, jwt, mock(HiringScope.class), mock(CandidateProfileService.class), mock(ScorecardService.class)).submitApplication(request(), "Bearer tok").block();

        verify(stub).applyAsApplicant(any(), eq(7L), eq("amy@example.com"));
    }

    @Test
    @DisplayName("an applicant's own copy of the new application leaves out the company's evidence")
    void applicantSeesNoCompanyEvidence() {
        ApplicationService stub = mock(ApplicationService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRoleFromToken("tok")).thenReturn("APPLICANT");
        when(jwt.getUserIdFromToken("tok")).thenReturn(7L);
        when(jwt.getEmailFromToken("tok")).thenReturn("amy@example.com");
        when(stub.applyAsApplicant(any(), any(), any())).thenReturn(Mono.just(com.airral.dto.response.ApplicationResponse.builder()
                .id(55L).jobId(JOB).atsScore(66).atsMatchedKeywords(java.util.List.of("Excel"))
                .atsMissingKeywords(java.util.List.of("Forklift")).visibleToHr(false).alignmentSource("RESUME_AND_NOTE").build()));

        var body = new ApplicationController(stub, jwt, mock(HiringScope.class), mock(CandidateProfileService.class),
                mock(ScorecardService.class)).submitApplication(request(), "Bearer tok").block().getBody();

        assertThat(body.getId()).isEqualTo(55L);
        assertThat(body.getAtsScore()).isNull();
        assertThat(body.getAtsMatchedKeywords()).isNull();
        assertThat(body.getAtsMissingKeywords()).isNull();
        assertThat(body.getVisibleToHr()).isNull();
        assertThat(body.getAlignmentSource()).isNull();
    }

    @Test
    @DisplayName("an employee cannot create an application")
    void employeeIsTurnedAway() {
        ApplicationService stub = mock(ApplicationService.class);
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.getRoleFromToken("tok")).thenReturn("EMPLOYEE");

        StepVerifier.create(new ApplicationController(stub, jwt, mock(HiringScope.class), mock(CandidateProfileService.class), mock(ScorecardService.class)).submitApplication(request(), "Bearer tok"))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN))
                .verifyComplete();
        verifyNoInteractions(stub);
    }
}
