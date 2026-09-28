package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.UserInvitation;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.InviteUserRequest;
import com.airral.dto.request.UpdateUserRequest;
import com.airral.exception.BadRequestException;
import com.airral.exception.ConflictException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What people in a company may do to each other's accounts: see a team, edit
 * a profile, invite someone. Runs the real UserService over mocked
 * repositories, so the rules under test are the ones production runs.
 */
class UserServiceRulesTest {

    private static final long ACME = 1L;
    private static final long GLOBEX = 2L;
    private static final long ACME_HR = 1L;

    private final UserRepository users = mock(UserRepository.class);
    private final UserInvitationRepository invitations = mock(UserInvitationRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private UserService service;

    private final User amy = person(7L, ACME, UserRole.EMPLOYEE);
    private final User ben = person(8L, ACME, UserRole.EMPLOYEE);
    private final User gus = person(50L, GLOBEX, UserRole.MANAGER);

    @BeforeEach
    void setUp() {
        service = new UserService(users, invitations, organizations, departments);
        when(organizations.findById(any(Long.class))).thenReturn(Mono.just(Organization.builder().name("Acme").build()));
        for (User user : new User[] {amy, ben, gus}) {
            when(users.findById(user.getId())).thenReturn(Mono.just(user));
        }
        when(users.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(invitations.save(any(UserInvitation.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private static User person(long id, long company, UserRole role) {
        return User.builder().id(id).email("u" + id + "@example.com").organizationId(company).role(role).build();
    }

    private static UpdateUserRequest rename(String firstName) {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setFirstName(firstName);
        return request;
    }

    private static InviteUserRequest invite(UserRole role) {
        return InviteUserRequest.builder().email("new@acme.io").role(role).build();
    }

    // ---- teams ----

    @Test
    @DisplayName("a manager's team only includes people from the caller's own company")
    void teamStaysInsideTheCompany() {
        User globexReport = person(51L, GLOBEX, UserRole.EMPLOYEE);
        globexReport.setManagerId(50L);
        when(users.findByManagerId(50L)).thenReturn(Flux.just(globexReport));

        StepVerifier.create(service.getTeamMembers(50L, ACME)).verifyComplete();
    }

    @Test
    @DisplayName("a manager's team in the caller's own company is listed")
    void teamInsideTheCompanyIsListed() {
        User report = person(9L, ACME, UserRole.EMPLOYEE);
        report.setManagerId(7L);
        when(users.findByManagerId(7L)).thenReturn(Flux.just(report));

        StepVerifier.create(service.getTeamMembers(7L, ACME))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(9L))
                .verifyComplete();
    }

    // ---- profiles ----

    @Test
    @DisplayName("an employee edits their own profile")
    void employeeEditsOwnProfile() {
        StepVerifier.create(service.updateUser(7L, rename("Amelia"), ACME, 7L, "EMPLOYEE"))
                .assertNext(response -> assertThat(response.getFirstName()).isEqualTo("Amelia"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a manager edits their own profile")
    void managerEditsOwnProfile() {
        when(users.findById(9L)).thenReturn(Mono.just(person(9L, ACME, UserRole.MANAGER)));

        StepVerifier.create(service.updateUser(9L, rename("Mia"), ACME, 9L, "MANAGER"))
                .assertNext(response -> assertThat(response.getFirstName()).isEqualTo("Mia"))
                .verifyComplete();
    }

    @Test
    @DisplayName("an employee cannot edit a colleague's profile")
    void employeeCannotEditColleague() {
        StepVerifier.create(service.updateUser(8L, rename("Benedict"), ACME, 7L, "EMPLOYEE"))
                .expectError(AccessDeniedException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("only HR moves someone to another manager or department")
    void onlyHrChangesStructure() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setManagerId(8L);

        StepVerifier.create(service.updateUser(7L, request, ACME, 7L, "EMPLOYEE"))
                .expectError(AccessDeniedException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("HR gives a colleague a manager from the same company")
    void hrAssignsManagerInCompany() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setManagerId(7L);

        StepVerifier.create(service.updateUser(8L, request, ACME, ACME_HR, "HR_MANAGER"))
                .assertNext(response -> assertThat(response.getManagerId()).isEqualTo(7L))
                .verifyComplete();
    }

    @Test
    @DisplayName("HR cannot give someone a manager from another company")
    void hrCannotAssignOutsideManager() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setManagerId(50L);

        StepVerifier.create(service.updateUser(8L, request, ACME, ACME_HR, "HR_MANAGER"))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("HR cannot put someone in another company's department")
    void hrCannotAssignOutsideDepartment() {
        when(departments.findByIdAndOrganizationId(30L, ACME)).thenReturn(Mono.empty());
        UpdateUserRequest request = new UpdateUserRequest();
        request.setDepartmentId(30L);

        StepVerifier.create(service.updateUser(8L, request, ACME, ACME_HR, "HR_MANAGER"))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("HR cannot edit someone at another company")
    void hrCannotEditOutsideCompany() {
        StepVerifier.create(service.updateUser(50L, rename("Gustav"), ACME, ACME_HR, "HR_MANAGER"))
                .expectError(NotFoundException.class)
                .verify();
        verify(users, never()).save(any());
    }

    // ---- invitations ----

    @Test
    @DisplayName("an invitation cannot make someone an AIRRAL admin")
    void inviteCannotGrantAdmin() {
        StepVerifier.create(service.inviteUser(invite(UserRole.ADMIN), ACME, ACME_HR))
                .expectError(BadRequestException.class)
                .verify();
        verify(invitations, never()).save(any());
    }

    @Test
    @DisplayName("an invitation cannot give the applicant role")
    void inviteCannotGrantApplicant() {
        StepVerifier.create(service.inviteUser(invite(UserRole.APPLICANT), ACME, ACME_HR))
                .expectError(BadRequestException.class)
                .verify();
        verify(invitations, never()).save(any());
    }

    @Test
    @DisplayName("inviting an address that has an applicant account gives a clear answer")
    void inviteExistingApplicant() {
        when(users.findByEmail("new@acme.io")).thenReturn(Mono.just(
                User.builder().id(60L).email("new@acme.io").role(UserRole.APPLICANT).build()));
        when(invitations.findValidInvitationByEmailAndOrganization("new@acme.io", ACME)).thenReturn(Mono.empty());

        StepVerifier.create(service.inviteUser(invite(UserRole.EMPLOYEE), ACME, ACME_HR))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(ConflictException.class)
                        .hasMessageContaining("applicant account"))
                .verify();
        verify(invitations, never()).save(any());
    }

    @Test
    @DisplayName("an HR manager invites a manager")
    void hrInvitesManager() {
        when(users.findByEmail("new@acme.io")).thenReturn(Mono.empty());
        when(invitations.findValidInvitationByEmailAndOrganization("new@acme.io", ACME)).thenReturn(Mono.empty());

        StepVerifier.create(service.inviteUser(invite(UserRole.MANAGER), ACME, ACME_HR))
                .assertNext(invitation -> {
                    assertThat(invitation.getRole()).isEqualTo(UserRole.MANAGER);
                    assertThat(invitation.getOrganizationId()).isEqualTo(ACME);
                })
                .verifyComplete();
    }
}
