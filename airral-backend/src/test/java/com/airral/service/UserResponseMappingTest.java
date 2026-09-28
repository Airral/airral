package com.airral.service;

import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Listing people who have no manager, which today is everyone: nothing in the
 * product sets a manager yet.
 */
class UserResponseMappingTest {

    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(users, mock(UserInvitationRepository.class), organizations,
                mock(DepartmentRepository.class), mock(FirebaseEmailLinkSender.class), mock(LoginThrottle.class),
                mock(com.airral.security.TokenVersionCache.class));
        when(organizations.findById(1L)).thenReturn(Mono.just(Organization.builder().id(1L).name("Acme").build()));
    }

    @Test
    @DisplayName("a company's people are listed even when none of them has a manager")
    void listsPeopleWithoutAManager() {
        when(users.findByOrganizationId(1L)).thenReturn(Flux.just(
                User.builder().id(7L).email("amy@acme.io").organizationId(1L).role(UserRole.HR_MANAGER).build()));

        StepVerifier.create(service.getAllUsers(1L))
                .assertNext(response -> {
                    assertThat(response.getOrganizationName()).isEqualTo("Acme");
                    assertThat(response.getManagerName()).isNull();
                })
                .verifyComplete();
    }
}
