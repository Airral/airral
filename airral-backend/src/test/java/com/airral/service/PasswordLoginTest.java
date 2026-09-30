package com.airral.service;

import com.airral.domain.User;
import com.airral.domain.enums.UserRole;
import com.airral.dto.request.LoginRequest;
import com.airral.exception.UnauthorizedException;
import com.airral.repository.CandidateProfileRepository;
import com.airral.repository.DepartmentRepository;
import com.airral.repository.OrganizationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signing in with a password. The account row is read before the password
 * check, which takes a while on purpose; writing that row back afterwards
 * would undo whatever HR changed in between, a deactivation included.
 */
class PasswordLoginTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private AuthService service;
    private User amy;

    @BeforeEach
    void setUp() {
        service = new AuthService(users, mock(OrganizationRepository.class), mock(CandidateProfileRepository.class),
                encoder, mock(JwtTokenProvider.class), new ObjectMapper(), mock(GoogleIdentityService.class),
                mock(TeamAlerts.class), mock(DepartmentRepository.class));
        amy = User.builder().id(5L).email("amy@example.com").passwordHash("hash").role(UserRole.APPLICANT)
                .isActive(true).tokenVersion(3).build();
        when(users.findByEmail("amy@example.com")).thenReturn(Mono.just(amy));
        when(encoder.matches("Secret123", "hash")).thenReturn(true);
        when(users.touchLastLogin(anyLong(), any())).thenReturn(Mono.just(1));
    }

    private Mono<?> signIn(String password) {
        return service.login(new LoginRequest("Amy@Example.com", password));
    }

    @Test
    @DisplayName("signing in writes the sign-in time and nothing else")
    void writesOnlyTheSignInTime() {
        StepVerifier.create(signIn("Secret123")).expectNextCount(1).verifyComplete();

        verify(users).touchLastLogin(eq(5L), any(LocalDateTime.class));
        verify(users, never()).save(any());
        assertThat(amy.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("a wrong password writes nothing")
    void wrongPasswordWritesNothing() {
        StepVerifier.create(signIn("wrong")).expectError(UnauthorizedException.class).verify();

        verify(users, never()).touchLastLogin(anyLong(), any());
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("a deactivated account is refused, and nothing is written")
    void deactivatedAccountIsRefused() {
        amy.setIsActive(false);

        StepVerifier.create(signIn("Secret123"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(UnauthorizedException.class)
                        .hasMessage("Account is deactivated"))
                .verify();
        verify(users, never()).touchLastLogin(anyLong(), any());
    }
}
