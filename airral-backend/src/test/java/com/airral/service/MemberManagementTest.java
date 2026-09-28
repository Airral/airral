package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.exception.BadRequestException;
import com.airral.exception.NotFoundException;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private final TokenVersionCache sessions = mock(TokenVersionCache.class);
    private UserService service;

    private final User ben = person(8L, ACME, UserRole.EMPLOYEE);
    private final User hana = person(9L, ACME, UserRole.HR_MANAGER);

    @BeforeEach
    void setUp() {
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        service = new UserService(users, mock(UserInvitationRepository.class), organizations,
                mock(DepartmentRepository.class), mock(FirebaseEmailLinkSender.class), mock(LoginThrottle.class), sessions);
        when(organizations.findById(any(Long.class))).thenReturn(Mono.just(Organization.builder().name("Acme").build()));
        when(users.findById(8L)).thenReturn(Mono.just(ben));
        when(users.findById(9L)).thenReturn(Mono.just(hana));
        when(users.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(2L));
        when(sessions.revokeAll(anyLong())).thenReturn(Mono.just(1));
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
    }

    @Test
    @DisplayName("nobody changes their own role")
    void cannotChangeOwnRole() {
        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, 9L))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("a member cannot be made an AIRRAL admin")
    void cannotGrantAdmin() {
        StepVerifier.create(service.changeRole(8L, UserRole.ADMIN, ACME, HR))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("the company's last HR manager cannot be moved to another role")
    void lastHrManagerKeepsTheRole() {
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.changeRole(9L, UserRole.EMPLOYEE, ACME, HR + 100))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
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
    }

    @Test
    @DisplayName("reactivating a member lets them back in without ending anything")
    void reactivation() {
        ben.setIsActive(false);

        StepVerifier.create(service.setActive(8L, true, ACME, HR))
                .assertNext(response -> assertThat(response.getIsActive()).isTrue())
                .verifyComplete();
        verify(sessions, never()).revokeAll(anyLong());
    }

    @Test
    @DisplayName("nobody deactivates themselves")
    void cannotDeactivateSelf() {
        StepVerifier.create(service.setActive(9L, false, ACME, 9L))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("the company's last active HR manager cannot be deactivated")
    void lastHrManagerStaysActive() {
        when(users.countActiveHrManagers(ACME)).thenReturn(Mono.just(1L));

        StepVerifier.create(service.setActive(9L, false, ACME, HR + 100))
                .expectError(BadRequestException.class)
                .verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("an AIRRAL admin's account is not the company's to change")
    void airralAdminIsOffLimits() {
        User admin = person(50L, ACME, UserRole.ADMIN);
        admin.setIsPlatformAdmin(true);
        when(users.findById(50L)).thenReturn(Mono.just(admin));

        StepVerifier.create(service.setActive(50L, false, ACME, HR)).expectError(AccessDeniedException.class).verify();
        StepVerifier.create(service.changeRole(50L, UserRole.EMPLOYEE, ACME, HR)).expectError(AccessDeniedException.class).verify();
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("HR cannot change members of another company")
    void otherCompanyIsOffLimits() {
        when(users.findById(60L)).thenReturn(Mono.just(person(60L, 2L, UserRole.EMPLOYEE)));

        StepVerifier.create(service.setActive(60L, false, ACME, HR)).expectError(NotFoundException.class).verify();
        verify(users, never()).save(any());
    }
}
