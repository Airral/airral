package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.ApplicantStage;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.dto.request.SubmitApplicationRequest;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an applicant sees of their application's stage, and which stage
 * changes email the candidate.
 */
class CandidateStageUpdatesTest {

    private static final long ACME = 1L;
    private static final long JOB = 10L;
    private static final long HANA = 3L;

    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final CandidateUpdateEmails emails = mock(CandidateUpdateEmails.class);

    private final ApplicationService applicationService = new ApplicationService(applications, jobs, users,
            organizations, mock(CandidateProfileRepository.class), emails, mock(com.airral.repository.CandidateResumeDocumentRepository.class),
            NoOffers.repository());
    private final InterviewService interviewService = new InterviewService(interviews, applications, jobs, users, emails, mock(InterviewerEmails.class));

    @BeforeEach
    void setUp() {
        when(jobs.findById(JOB)).thenReturn(Mono.just(
                Job.builder().id(JOB).organizationId(ACME).title("Backend engineer").build()));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        when(users.findById(any(Long.class))).thenReturn(Mono.just(User.builder().id(HANA).firstName("Hana").build()));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(interviews.findInterviewerIds(any())).thenReturn(Flux.empty());
        when(interviews.save(any(Interview.class))).thenAnswer(inv -> {
            Interview interview = inv.getArgument(0);
            interview.setId(900L);
            return Mono.just(interview);
        });
    }

    private Application candidateAt(long id, ApplicationStatus status) {
        Application application = Application.builder().id(id).jobId(JOB).applicantId(7L)
                .applicantName("Amy Adams").applicantEmail("amy@example.com").status(status)
                .atsScore(42).reviewedByHrId(HANA).build();
        when(applications.findByIdAndOrganizationId(id, ACME)).thenReturn(Mono.just(application));
        when(applications.findById(id)).thenReturn(Mono.just(application));
        return application;
    }

    @Test
    @DisplayName("an applicant sees each application's stage in their terms, with the company's name, newest first")
    void applicantSeesTheirStage() {
        Application newest = candidateAt(3L, ApplicationStatus.SHORTLISTED);
        Application middle = candidateAt(2L, ApplicationStatus.INTERVIEWED);
        Application oldest = candidateAt(1L, ApplicationStatus.REJECTED);
        when(applications.findByApplicantId(7L)).thenReturn(Flux.just(newest, middle, oldest));

        StepVerifier.create(applicationService.getMyApplications(7L))
                .assertNext(mine -> {
                    assertThat(mine.getId()).isEqualTo(3L);
                    assertThat(mine.getStage()).isEqualTo(ApplicantStage.IN_REVIEW);
                    assertThat(mine.getCompanyName()).isEqualTo("Acme");
                    assertThat(mine.getJobTitle()).isEqualTo("Backend engineer");
                })
                .assertNext(mine -> assertThat(mine.getStage()).isEqualTo(ApplicantStage.INTERVIEWING))
                .assertNext(mine -> assertThat(mine.getStage()).isEqualTo(ApplicantStage.NOT_SELECTED))
                .verifyComplete();
    }

    @Test
    @DisplayName("every stage a company can set has an applicant stage")
    void everyStageMaps() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(ApplicantStage.of(status)).as(status.name()).isNotNull();
        }
        assertThat(ApplicantStage.of(ApplicationStatus.SUBMITTED)).isEqualTo(ApplicantStage.APPLIED);
        assertThat(ApplicantStage.of(ApplicationStatus.OFFER_EXTENDED)).isEqualTo(ApplicantStage.OFFER);
        assertThat(ApplicantStage.of(ApplicationStatus.HIRED)).isEqualTo(ApplicantStage.HIRED);
        assertThat(ApplicantStage.of(ApplicationStatus.WITHDRAWN)).isEqualTo(ApplicantStage.WITHDRAWN);
    }

    @Test
    @DisplayName("turning a candidate down emails them only when HR asks")
    void rejectionEmailIsOptIn() {
        Application quiet = candidateAt(1L, ApplicationStatus.UNDER_REVIEW);
        Application told = candidateAt(2L, ApplicationStatus.UNDER_REVIEW);

        applicationService.updateApplicationStatus(1L, ApplicationStatus.REJECTED, ACME, HANA,
                JobScope.wholeCompany(), false).block();
        verify(emails, never()).notSelected(quiet);

        applicationService.updateApplicationStatus(2L, ApplicationStatus.REJECTED, ACME, HANA,
                JobScope.wholeCompany(), true).block();
        verify(emails).notSelected(told);
    }

    @Test
    @DisplayName("a candidate who is already turned down is not emailed again, and other stages send nothing")
    void rejectionEmailOnlyOnTheWayIn() {
        candidateAt(1L, ApplicationStatus.REJECTED);
        candidateAt(2L, ApplicationStatus.UNDER_REVIEW);

        applicationService.updateApplicationStatus(1L, ApplicationStatus.REJECTED, ACME, HANA,
                JobScope.wholeCompany(), true).block();
        applicationService.updateApplicationStatus(2L, ApplicationStatus.SHORTLISTED, ACME, HANA,
                JobScope.wholeCompany(), true).block();

        verify(emails, never()).notSelected(any());
    }

    @Test
    @DisplayName("booking an interview emails the candidate only when HR asks")
    void interviewEmailIsOptIn() {
        Application amy = candidateAt(1L, ApplicationStatus.SHORTLISTED);
        ScheduleInterviewRequest booking = new ScheduleInterviewRequest();
        booking.setApplicationId(1L);
        booking.setInterviewDate(LocalDateTime.of(2026, 10, 1, 14, 0));

        interviewService.scheduleInterview(booking, ACME, HANA, JobScope.wholeCompany()).block();
        verify(emails, never()).interviewBooked(any(), any());

        booking.setNotifyCandidate(true);
        interviewService.scheduleInterview(booking, ACME, HANA, JobScope.wholeCompany()).block();
        verify(emails).interviewBooked(eq(amy), any(Interview.class));
    }

    @Test
    @DisplayName("a resume link HR types must be a web address")
    void resumeLinkIsAWebAddress() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        SubmitApplicationRequest request = SubmitApplicationRequest.builder()
                .jobId(JOB).applicantName("Hal Hughes").applicantEmail("hal@example.com").build();

        for (String allowed : new String[] {null, "", "https://drive.example.com/cv.pdf", "HTTP://example.com/cv"}) {
            request.setResumeUrl(allowed);
            assertThat(validator.validate(request)).as(String.valueOf(allowed)).isEmpty();
        }
        for (String refused : new String[] {"javascript:alert(1)", "data:text/html,hi", "cv.pdf", "https://a b"}) {
            request.setResumeUrl(refused);
            assertThat(validator.validate(request)).as(refused)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("resumeUrl");
        }
    }
}
