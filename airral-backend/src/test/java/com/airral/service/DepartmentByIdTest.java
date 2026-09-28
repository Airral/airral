package com.airral.service;

import com.airral.domain.Department;
import com.airral.domain.Job;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.UserInvitation;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.CreateJobRequest;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.request.UpdateUserRequest;
import com.airral.exception.BadRequestException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.JobRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A job, a person or an invitation is filed under one of the company's own
 * departments, by id, and its department name is copied from that department.
 * Typed department text is ignored.
 */
class DepartmentByIdTest {

    private static final long ACME = 1L;

    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final UserInvitationRepository invitations = mock(UserInvitationRepository.class);
    private final InternalJobCatalogProjectionService catalogue = mock(InternalJobCatalogProjectionService.class);
    private final LoginThrottle throttle = mock(LoginThrottle.class);
    private final FirebaseEmailLinkSender linkSender = mock(FirebaseEmailLinkSender.class);
    private JobService jobService;
    private UserService userService;
    private User ben;

    @BeforeEach
    void setUp() {
        jobService = new JobService(jobs, users, organizations, mock(ExternalJobPostingStore.class), catalogue, departments);
        userService = new UserService(users, invitations, organizations, departments, linkSender, throttle,
                mock(TokenVersionCache.class));

        when(departments.findByIdAndOrganizationId(5L, ACME)).thenReturn(Mono.just(
                Department.builder().id(5L).organizationId(ACME).name("Engineering").build()));
        when(departments.findByIdAndOrganizationId(6L, ACME)).thenReturn(Mono.empty());
        when(organizations.findById(ACME)).thenReturn(Mono.just(Organization.builder().id(ACME).name("Acme").build()));
        when(jobs.save(any(Job.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(catalogue.sync(any(Job.class))).thenReturn(Mono.empty());
        ben = User.builder().id(8L).email("ben@acme.io").organizationId(ACME).role(UserRole.EMPLOYEE)
                .department("Old text").isActive(true).build();
        when(users.findById(8L)).thenReturn(Mono.just(ben));
        when(users.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static CreateJobRequest job(Long departmentId, String typedDepartment) {
        return CreateJobRequest.builder().title("Backend engineer").description("Build the API")
                .departmentId(departmentId).department(typedDepartment).build();
    }

    @Test
    @DisplayName("a job's department name comes from the company's department, not from what was typed")
    void jobTakesTheDepartmentsName() {
        StepVerifier.create(jobService.createJob(job(5L, "Typed text"), ACME, null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getDepartmentId()).isEqualTo(5L);
        assertThat(saved.getValue().getDepartment()).isEqualTo("Engineering");
    }

    @Test
    @DisplayName("a job cannot be filed under another company's department")
    void jobRefusesAnotherCompanysDepartment() {
        StepVerifier.create(jobService.createJob(job(6L, null), ACME, null))
                .expectError(BadRequestException.class)
                .verify();
        verify(jobs, never()).save(any());
    }

    @Test
    @DisplayName("a job without a department has no department name either")
    void jobWithoutDepartment() {
        StepVerifier.create(jobService.createJob(job(null, "Typed text"), ACME, null)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        verify(jobs).save(saved.capture());
        assertThat(saved.getValue().getDepartmentId()).isNull();
        assertThat(saved.getValue().getDepartment()).isNull();
    }

    @Test
    @DisplayName("HR files a person under a department, and the name follows the department")
    void personTakesTheDepartmentsName() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setDepartmentId(5L);
        request.setDepartment("Typed text");

        StepVerifier.create(userService.updateUser(8L, request, ACME, 1L, "HR_MANAGER"))
                .assertNext(response -> {
                    assertThat(response.getDepartmentId()).isEqualTo(5L);
                    assertThat(response.getDepartment()).isEqualTo("Engineering");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("HR takes a person out of their department")
    void hrClearsADepartment() {
        ben.setDepartmentId(5L);
        UpdateUserRequest request = new UpdateUserRequest();
        request.setClearDepartment(true);

        StepVerifier.create(userService.updateUser(8L, request, ACME, 1L, "HR_MANAGER"))
                .assertNext(response -> {
                    assertThat(response.getDepartmentId()).isNull();
                    assertThat(response.getDepartment()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an employee cannot take themselves out of their department")
    void employeeCannotClearTheirDepartment() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setClearDepartment(true);

        StepVerifier.create(userService.updateUser(8L, request, ACME, 8L, "EMPLOYEE"))
                .expectError(AccessDeniedException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("an invitation's department name comes from the company's department")
    void invitationTakesTheDepartmentsName() {
        when(users.findByEmail(any())).thenReturn(Mono.empty());
        when(invitations.findValidInvitationByEmailAndOrganization(any(), any())).thenReturn(Mono.empty());
        when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(throttle.invitationEmailAllowed(ACME)).thenReturn(Mono.just(true));
        when(throttle.recordInvitationEmail(ACME)).thenReturn(Mono.empty());
        when(linkSender.sendInvitation(any(), any(), any())).thenReturn(Mono.empty());

        InviteUserRequest request = InviteUserRequest.builder().email("new@acme.io").role(UserRole.EMPLOYEE)
                .departmentId(5L).department("Typed text").build();
        StepVerifier.create(userService.inviteUser(request, ACME, 1L))
                .assertNext(response -> assertThat(response.getDepartment()).isEqualTo("Engineering"))
                .verifyComplete();
    }
}
