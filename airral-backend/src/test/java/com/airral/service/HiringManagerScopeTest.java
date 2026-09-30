package com.airral.service;

import com.airral.domain.Application;
import com.airral.domain.HrEncounter;
import com.airral.domain.Interview;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.ApplicationStatus;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.CreateEncounterRequest;
import com.airral.dto.request.CreateJobRequest;
import com.airral.dto.request.InterviewFeedbackRequest;
import com.airral.dto.request.ScheduleInterviewRequest;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.ApplicationRepository;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.HrEncounterRepository;
import com.airral.repository.InterviewRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hiring manager works on the jobs they are hiring manager on, and nothing
 * else in the company: its applications, interviews and notes. HR works on
 * every job. Runs the real services over mocked repositories.
 */
class HiringManagerScopeTest {

    private static final long ACME = 1L;
    private static final long MIA = 20L;
    private static final long MIAS_JOB = 10L;
    private static final long OTHER_JOB = 11L;
    private static final JobScope MIAS_SCOPE = JobScope.only(Set.of(MIAS_JOB));

    private final JobRepository jobs = mock(JobRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final HrEncounterRepository encounters = mock(HrEncounterRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);

    private ApplicationService applicationService;
    private InterviewService interviewService;
    private HrEncounterService encounterService;

    private final Application onMiasJob = application(100L, MIAS_JOB);
    private final Application onOtherJob = application(101L, OTHER_JOB);

    @BeforeEach
    void setUp() {
        applicationService = new ApplicationService(applications, jobs, users, organizations,
                mock(com.airral.repository.CandidateProfileRepository.class), mock(CandidateUpdateEmails.class), mock(com.airral.repository.CandidateResumeDocumentRepository.class),
                NoOffers.repository());
        interviewService = new InterviewService(interviews, applications, jobs, users, mock(CandidateUpdateEmails.class), mock(InterviewerEmails.class));
        encounterService = new HrEncounterService(encounters, applications, jobs, users);

        when(jobs.findById(any(Long.class))).thenAnswer(inv -> Mono.just(
                Job.builder().id(inv.getArgument(0)).organizationId(ACME).title("Job " + inv.getArgument(0)).build()));
        when(users.findById(any(Long.class))).thenReturn(Mono.just(User.builder().id(1L).firstName("Hana").build()));
        for (Application application : new Application[] {onMiasJob, onOtherJob}) {
            when(applications.findByIdAndOrganizationId(application.getId(), ACME)).thenReturn(Mono.just(application));
            when(applications.findById(application.getId())).thenReturn(Mono.just(application));
        }
        when(applications.findAllByOrganizationId(ACME)).thenReturn(Flux.just(onMiasJob, onOtherJob));
        when(applications.save(any(Application.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(interviews.findInterviewerIds(any())).thenReturn(Flux.empty());
    }

    private static Application application(long id, long jobId) {
        return Application.builder().id(id).jobId(jobId).applicantName("Candidate " + id)
                .applicantEmail("c" + id + "@example.com").status(ApplicationStatus.SUBMITTED).build();
    }

    private static Interview interview(long id, long applicationId) {
        return Interview.builder().id(id).applicationId(applicationId).scheduledById(1L)
                .interviewDate(LocalDateTime.now().plusDays(1)).status("SCHEDULED").build();
    }

    private static HrEncounter note(long id, long applicationId, long jobId) {
        return HrEncounter.builder().id(id).organizationId(ACME).applicationId(applicationId).jobId(jobId)
                .performedById(1L).encounterType("NOTE").title("Note " + id).build();
    }

    @Test
    @DisplayName("a manager's scope is the jobs they are hiring manager on; HR's is the whole company")
    void scopeFollowsTheRole() {
        when(jobs.findByOrganizationIdAndHiringManagerId(ACME, MIA))
                .thenReturn(Flux.just(Job.builder().id(MIAS_JOB).organizationId(ACME).build()));
        HiringScope hiringScope = new HiringScope(jobs);

        StepVerifier.create(hiringScope.of(ACME, MIA, "MANAGER"))
                .assertNext(scope -> {
                    assertThat(scope.allows(MIAS_JOB)).isTrue();
                    assertThat(scope.allows(OTHER_JOB)).isFalse();
                })
                .verifyComplete();
        StepVerifier.create(hiringScope.of(ACME, 1L, "HR_MANAGER"))
                .assertNext(scope -> assertThat(scope.isWholeCompany()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("a manager sees only the applications for their jobs, and HR sees them all")
    void applicationsFollowTheScope() {
        StepVerifier.create(applicationService.getAllApplications(ACME, MIAS_SCOPE))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(100L))
                .verifyComplete();
        StepVerifier.create(applicationService.getAllApplications(ACME, JobScope.wholeCompany()))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    @DisplayName("a manager cannot open or move a candidate on someone else's job")
    void managerCannotReachAnotherJobsCandidate() {
        StepVerifier.create(applicationService.getApplicationById(101L, ACME, MIAS_SCOPE))
                .expectError(NotFoundException.class).verify();
        StepVerifier.create(applicationService.updateApplicationStatus(101L, ApplicationStatus.SHORTLISTED, ACME, MIA, MIAS_SCOPE))
                .expectError(NotFoundException.class).verify();
        StepVerifier.create(applicationService.getApplicationsByJob(OTHER_JOB, ACME, MIAS_SCOPE)).verifyComplete();
        verify(applications, never()).save(any());
    }

    @Test
    @DisplayName("a manager sees only the interviews for their jobs, and cannot book or grade another job's")
    void interviewsFollowTheScope() {
        when(interviews.findAllByOrganizationId(ACME)).thenReturn(Flux.just(interview(1000L, 100L), interview(1001L, 101L)));
        when(interviews.findByIdAndOrganizationId(1001L, ACME)).thenReturn(Mono.just(interview(1001L, 101L)));

        StepVerifier.create(interviewService.getAllInterviews(ACME, MIAS_SCOPE))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(1000L))
                .verifyComplete();

        ScheduleInterviewRequest booking = new ScheduleInterviewRequest();
        booking.setApplicationId(101L);
        booking.setInterviewDate(LocalDateTime.now().plusDays(2));
        StepVerifier.create(interviewService.scheduleInterview(booking, ACME, MIA, MIAS_SCOPE))
                .expectError(NotFoundException.class).verify();

        StepVerifier.create(interviewService.submitFeedback(1001L, new InterviewFeedbackRequest(), ACME, MIAS_SCOPE))
                .expectError(NotFoundException.class).verify();
        verify(interviews, never()).save(any());
    }

    @Test
    @DisplayName("a manager sees only the notes on their jobs, and cannot add one to another job's candidate")
    void notesFollowTheScope() {
        when(encounters.findByOrganizationId(ACME)).thenReturn(Flux.just(note(500L, 100L, MIAS_JOB), note(501L, 101L, OTHER_JOB)));

        StepVerifier.create(encounterService.getAllEncounters(ACME, MIAS_SCOPE))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(500L))
                .verifyComplete();

        CreateEncounterRequest request = new CreateEncounterRequest();
        request.setApplicationId(101L);
        request.setEncounterType("NOTE");
        request.setTitle("Strong system design");
        StepVerifier.create(encounterService.createEncounter(request, ACME, MIA, MIAS_SCOPE))
                .expectError(NotFoundException.class).verify();
        verify(encounters, never()).save(any());
    }

    // ---- choosing a hiring manager ----

    private JobService jobServiceWith(User candidateHiringManager) {
        InternalJobCatalogProjectionService catalogue = mock(InternalJobCatalogProjectionService.class);
        when(catalogue.sync(any(Job.class))).thenReturn(Mono.empty());
        JobService service = new JobService(jobs, users, organizations, mock(ExternalJobPostingStore.class),
                catalogue, mock(DepartmentRepository.class), mock(com.airral.repository.InterviewKitRepository.class));
        when(users.findById(candidateHiringManager.getId())).thenReturn(Mono.just(candidateHiringManager));
        return service;
    }

    private static CreateJobRequest jobManagedBy(long userId) {
        return CreateJobRequest.builder().title("Backend engineer").description("Build the API").hiringManagerId(userId).build();
    }

    @Test
    @DisplayName("a job's hiring manager must be an active manager or HR manager in the company")
    void hiringManagerMustBeInTheCompany() {
        User employee = User.builder().id(30L).organizationId(ACME).role(UserRole.EMPLOYEE).isActive(true).build();
        User otherCompany = User.builder().id(31L).organizationId(2L).role(UserRole.MANAGER).isActive(true).build();
        User deactivated = User.builder().id(32L).organizationId(ACME).role(UserRole.MANAGER).isActive(false).build();

        for (User unsuitable : new User[] {employee, otherCompany, deactivated}) {
            StepVerifier.create(jobServiceWith(unsuitable).createJob(jobManagedBy(unsuitable.getId()), ACME, 1L))
                    .expectError(BadRequestException.class)
                    .verify();
        }
        verify(jobs, never()).save(any());
    }

    @Test
    @DisplayName("a manager in the company can be a job's hiring manager, and the job names them")
    void managerBecomesHiringManager() {
        User mia = User.builder().id(MIA).organizationId(ACME).role(UserRole.MANAGER).isActive(true)
                .firstName("Mia").lastName("Moss").build();
        JobService service = jobServiceWith(mia);
        when(jobs.save(any(Job.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));

        StepVerifier.create(service.createJob(jobManagedBy(MIA), ACME, null))
                .assertNext(response -> {
                    assertThat(response.getHiringManagerId()).isEqualTo(MIA);
                    assertThat(response.getHiringManagerName()).isEqualTo(mia.getFullName());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a job whose hiring manager has since left hiring can still be edited; a new one is checked")
    void staleHiringManagerDoesNotBlockEdits() {
        User movedOn = User.builder().id(30L).organizationId(ACME).role(UserRole.EMPLOYEE).isActive(true).build();
        JobService service = jobServiceWith(movedOn);
        Job job = Job.builder().id(MIAS_JOB).organizationId(ACME).title("Backend engineer").hiringManagerId(30L).build();
        when(jobs.findByIdAndOrganizationId(MIAS_JOB, ACME)).thenReturn(Mono.just(job));
        when(jobs.save(any(Job.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));

        CreateJobRequest retitled = jobManagedBy(30L);
        retitled.setTitle("Senior backend engineer");
        StepVerifier.create(service.updateJob(MIAS_JOB, retitled, ACME))
                .assertNext(response -> assertThat(response.getTitle()).isEqualTo("Senior backend engineer"))
                .verifyComplete();

        User employee = User.builder().id(33L).organizationId(ACME).role(UserRole.EMPLOYEE).isActive(true).build();
        when(users.findById(33L)).thenReturn(Mono.just(employee));
        StepVerifier.create(service.updateJob(MIAS_JOB, jobManagedBy(33L), ACME))
                .expectError(BadRequestException.class)
                .verify();
    }

    @Test
    @DisplayName("a manager's recent notes are their own newest, not their share of the company's newest")
    void recentNotesLimitAfterScope() {
        when(encounters.findRecentByOrganizationId(eq(ACME), any(), eq(HrEncounterService.SCOPED_SCAN)))
                .thenReturn(Flux.just(note(503L, 101L, OTHER_JOB), note(502L, 101L, OTHER_JOB),
                        note(501L, 100L, MIAS_JOB), note(500L, 100L, MIAS_JOB)));

        StepVerifier.create(encounterService.getRecentEncounters(ACME, 2, MIAS_SCOPE))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(501L))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(500L))
                .verifyComplete();

        when(encounters.findRecentByOrganizationId(eq(ACME), any(), eq(2)))
                .thenReturn(Flux.just(note(503L, 101L, OTHER_JOB), note(502L, 101L, OTHER_JOB)));
        StepVerifier.create(encounterService.getRecentEncounters(ACME, 2, JobScope.wholeCompany()))
                .expectNextCount(2)
                .verifyComplete();
    }
}
