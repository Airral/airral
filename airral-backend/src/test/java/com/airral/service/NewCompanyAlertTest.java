package com.airral.service;

import com.airral.domain.Department;
import com.airral.domain.Organization;
import com.airral.domain.User;
import com.airral.domain.enums.OrganizationTier;
import com.airral.dto.request.RegisterRequest;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every new company waits for review, so the team has to hear that it signed
 * up. Runs the real AuthService registration over mocked repositories.
 */
class NewCompanyAlertTest {

    @Test
    @DisplayName("signing up creates the company and its first HR manager, and tells nobody yet")
    void signupCreatesTheCompany() {
        UserRepository users = mock(UserRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        TeamAlerts alerts = mock(TeamAlerts.class);
        DepartmentRepository departments = mock(DepartmentRepository.class);
        when(departments.save(any(Department.class))).thenAnswer(inv -> {
            Department department = inv.getArgument(0);
            department.setId(3L);
            return Mono.just(department);
        });

        when(encoder.encode(any())).thenReturn("hash");
        when(users.existsByEmail("amy@acme.io")).thenReturn(Mono.just(false));
        when(organizations.existsVerifiedDomainOtherThan("acme.io", -1L)).thenReturn(Mono.just(false));
        when(organizations.save(any(Organization.class))).thenAnswer(inv -> {
            Organization org = inv.getArgument(0);
            org.setId(4L);
            return Mono.just(org);
        });
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            user.setId(7L);
            return Mono.just(user);
        });
        when(organizations.findById(4L)).thenReturn(Mono.just(
                Organization.builder().id(4L).name("Acme").tier(OrganizationTier.QUICK_HIRE).build()));

        AuthService auth = new AuthService(users, organizations, mock(CandidateProfileRepository.class), encoder,
                mock(JwtTokenProvider.class), new ObjectMapper(), mock(GoogleIdentityService.class), departments);

        RegisterRequest request = new RegisterRequest();
        request.setEmail("amy@acme.io");
        request.setPassword("Secret123");
        request.setFirstName("Amy");
        request.setLastName("Adams");
        request.setCompanyName("Acme");

        StepVerifier.create(auth.register(request)).expectNextCount(1).verifyComplete();

        // The team hears once the address is proven (NewCompanyAnnouncementTest):
        // a bot's sign-up never gets that far.
        verifyNoInteractions(alerts);
        // The first HR manager is filed under the company's first department.
        verify(departments).save(argThat(department -> "Human Resources".equals(department.getName())
                && Long.valueOf(4L).equals(department.getOrganizationId())));
        verify(users).save(argThat(user -> Long.valueOf(3L).equals(user.getDepartmentId())
                && "Human Resources".equals(user.getDepartment())));
    }
}
