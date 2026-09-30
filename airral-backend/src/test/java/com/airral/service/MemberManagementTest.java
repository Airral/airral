package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
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
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HR changing a member's role or switching their account off. Runs the real
 * UserService over mocked repositories.
 */
class MemberManagementTest {

    private static final long ACME = 1L;
    private static final long HR = 1L;

    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final TokenVersionCache sessions = mock(TokenVersionCache.class);
    private UserService service;

    private final User ben = person(8L, ACME, UserRole.EMPLOYEE);
    private final User hana = person(9L, ACME, UserRole.HR_MANAGER);
    private final Map<Long, User> stored = new java.util.HashMap<>(Map.of(8L, ben, 9L, hana));

    @BeforeEach
    void setUp() {
        service = new UserService(users, mock(UserInvitationRepository.class), organizations,
                mock(DepartmentRepository.class), mock(FirebaseEmailLinkSender.class), mock(LoginThrottle.class), sessions, jobs);
        when(organizations.findById(any(Long.class))).thenReturn(Mono.just(Organization.builder().name("Acme").build()));
        when(organizations.lockForUpdate(ACME)).thenReturn(Mono.just(ACME));
        when(users.findById(any(Long.class))).thenAnswer(inv -> Mono.justOrEmpty(stored.get(inv.<Long>getArgument(0))));
        // The targeted writes change the stored row the next read returns.
        when(users.setRole(anyLong(), anyLong(), anyString(), any(LocalDateTime.class))).thenAnswer(inv -> {
            stored.get(inv.<Long>getArgument(0)).setRole(UserRole.valueOf(inv.getArgument(2)));
            return Mono.just(1);
        });
        when(users.setActive(anyLong(), anyLong(), anyBoolean(), any(LocalDateTime.class))).thenAnswer(inv -> {
            stored.get(inv.<Long>getArgument(0)).setIsActive(inv.getArgument(2));
            return Mono.just(1);
        });
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(2L));
        when(jobs.clearHiringManager(anyLong())).thenReturn(Mono.just(0L));
        when(sessions.revokeAll(anyLong())).thenReturn(Mono.just(1));
    }

    /** Nothing about the member was written. */
    private void nothingWritten() {
        verify(users, never()).save(any());
        verify(users, never()).setRole(anyLong(), anyLong(), anyString(), any());
        verify(users, never()).setActive(anyLong(), anyLong(), anyBoolean(), any());
        verify(jobs, never()).clearHiringManager(anyLong());
        verify(sessions, never()).revokeAll(anyLong());
    }

    private static User person(long id, long company, UserRole role) {
        return User.builder().id(id).email("u" + id + "@acme.io").organizationId(company).role(role)
                .isActive(true).isPlatformAdmin(false).build();
    }

    @Test
    @DisplayName("HR gives a member a new role, and the member's sessions end so it takes effect")
    void roleChangeSignsTheMemberOut() {
        StepVerifier.create(service.changeRole(8L, UserRole.MANAGER, ACME, HR))
                .assertNext(response -> assertThat(response.getRole()).isEqualTo(UserRole.MANAGER))
                .verifyComplete();
        verify(sessions).revokeAll(8L);
        // Only the role column: a full-row save would write back whatever else
        // was read, undoing a change made in the meantime.
        verify(users).setRole(eq(8L), eq(ACME), eq("MANAGER"), any(LocalDateTime.class));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("the company is locked before the keep-one-HR check, so two demotions cannot both pass it")
    void companyLockedBeforeTheCheck() {
        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, HR + 100))
                .expectNextCount(1)
                .verifyComplete();

        InOrder order = inOrder(organizations, users);
        order.verify(organizations).lockForUpdate(ACME);
        order.verify(users).countActiveHrManagers(ACME);
        order.verify(users).setRole(eq(9L), eq(ACME), eq("EMPLOYEE"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("a member moved to employee stops being the hiring manager of any job")
    void demotionReleasesTheirJobs() {
        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, HR + 100))
                .expectNextCount(1)
                .verifyComplete();
        verify(jobs).clearHiringManager(9L);
    }

    @Test
    @DisplayName("a member moved to manager keeps the jobs they manage")
    void managerKeepsTheirJobs() {
        StepVerifier.create(service.changeRole(9L, UserRole.MANAGER, ACME, HR + 100))
                .expectNextCount(1)
                .verifyComplete();
        verify(jobs, never()).clearHiringManager(anyLong());
    }

    @Test
    @DisplayName("giving a member the role they already have changes nothing")
    void sameRoleIsANoOp() {
        StepVerifier.create(service.changeRole(8L, UserRole.EMPLOYEE, ACME, HR))
                .assertNext(response -> assertThat(response.getRole()).isEqualTo(UserRole.EMPLOYEE))
                .verifyComplete();
        nothingWritten();
    }

    @Test
    @DisplayName("nobody changes their own role")
    void cannotChangeOwnRole() {
        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, 9L))
                .expectError(BadRequestException.class)
                .verify();
        nothingWritten();
    }

    @Test
    @DisplayName("a member cannot be made an AIRRAL admin")
    void cannotGrantAdmin() {
        StepVerifier.create(service.changeRole(8L, UserRole.ADMIN, ACME, HR))
                .expectError(BadRequestException.class)
                .verify();
        nothingWritten();
    }

    @Test
    @DisplayName("the company's last HR manager cannot be moved to another role")
    void lastHrManagerKeepsTheRole() {
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, HR + 100))
                .expectError(BadRequestException.class)
                .verify();
        nothingWritten();
    }

    @Test
    @DisplayName("an HR manager can move to another role while another HR manager remains")
    void hrManagerMovesWhileAnotherRemains() {
        StepVerifier.create(service.changeRole(9L, UserRole.MANAGER, ACME, HR + 100))
                .assertNext(response -> assertThat(response.getRole()).isEqualTo(UserRole.MANAGER))
                .verifyComplete();
    }

    @Test
    @DisplayName("deactivating a member switches the account off and ends their sessions")
    void deactivationSignsTheMemberOut() {
        StepVerifier.create(service.setActive(8L, false, ACME, HR))
                .assertNext(response -> assertThat(response.getIsActive()).isFalse())
                .verifyComplete();
        verify(sessions).revokeAll(8L);
        verify(users).setActive(eq(8L), eq(ACME), eq(false), any(LocalDateTime.class));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("a deactivated member stops being the hiring manager of any job")
    void deactivationReleasesTheirJobs() {
        StepVerifier.create(service.setActive(8L, false, ACME, HR)).expectNextCount(1).verifyComplete();
        verify(jobs).clearHiringManager(8L);
    }

    @Test
    @DisplayName("reactivating a member lets them back in without ending anything")
    void reactivation() {
        ben.setIsActive(false);

        StepVerifier.create(service.setActive(8L, true, ACME, HR))
                .assertNext(response -> assertThat(response.getIsActive()).isTrue())
                .verifyComplete();
        verify(sessions, never()).revokeAll(anyLong());
        verify(jobs, never()).clearHiringManager(anyLong());
    }

    @Test
    @DisplayName("nobody deactivates themselves")
    void cannotDeactivateSelf() {
        StepVerifier.create(service.setActive(9L, false, ACME, 9L))
                .expectError(BadRequestException.class)
                .verify();
        nothingWritten();
    }

    @Test
    @DisplayName("the company's last active HR manager cannot be deactivated")
    void lastHrManagerStaysActive() {
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.setActive(9L, false, ACME, HR + 100))
                .expectError(BadRequestException.class)
                .verify();
        nothingWritten();
    }

    @Test
    @DisplayName("an AIRRAL admin's account is not the company's to change")
    void airralAdminIsOffLimits() {
        User admin = person(50L, ACME, UserRole.ADMIN);
        admin.setIsPlatformAdmin(true);
        stored.put(50L, admin);

        StepVerifier.create(service.setActive(50L, false, ACME, HR)).expectError(AccessDeniedException.class).verify();
        StepVerifier.create(service.changeRole(50L, UserRole.EMPLOYEE, ACME, HR)).expectError(AccessDeniedException.class).verify();
        nothingWritten();
    }

    @Test
    @DisplayName("HR cannot change members of another company")
    void otherCompanyIsOffLimits() {
        stored.put(60L, person(60L, 2L, UserRole.EMPLOYEE));

        StepVerifier.create(service.setActive(60L, false, ACME, HR)).expectError(NotFoundException.class).verify();
        nothingWritten();
    }
}
