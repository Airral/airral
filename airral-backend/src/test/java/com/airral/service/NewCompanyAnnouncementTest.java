package com.airral.service;

import com.airral.domain.User;
import com.airral.repository.UserInvitationRepository;
import com.airral.repository.UserRepository;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The team hears about a new company once someone proves the address it signed
 * up with, not at sign-up: a bot's made-up inbox never gets that far.
 */
class NewCompanyAnnouncementTest {

    private final FirebaseIdentityService firebase = mock(FirebaseIdentityService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CompanyVerificationService companies = mock(CompanyVerificationService.class);
    private AccountVerificationService service;
    private User amy;

    @BeforeEach
    void setUp() {
        service = new AccountVerificationService(firebase, users, mock(PasswordEncoder.class),
                mock(TokenVersionCache.class), mock(LoginThrottle.class), companies,
                mock(FirebaseEmailLinkSender.class), mock(UserInvitationRepository.class));
        amy = User.builder().id(7L).email("amy@acme.io").organizationId(4L).isActive(true).emailVerified(false).build();
        when(firebase.verifyIdToken("link")).thenReturn(Mono.just(
                new FirebaseIdentityService.VerifiedEmail("amy@acme.io", "uid", Instant.now())));
        when(users.findByEmail("amy@acme.io")).thenReturn(Mono.just(amy));
        when(users.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(companies.onEmailProven(any())).thenReturn(Mono.empty());
        when(companies.announceNewCompany(any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("proving the sign-up address announces the company")
    void firstProofAnnounces() {
        StepVerifier.create(service.verifyEmail("link")).expectNextCount(1).verifyComplete();

        verify(companies).announceNewCompany(amy);
    }

    @Test
    @DisplayName("following the link again announces nothing")
    void secondProofIsQuiet() {
        amy.setEmailVerified(true);

        StepVerifier.create(service.verifyEmail("link")).expectNextCount(1).verifyComplete();

        verify(companies, never()).announceNewCompany(any());
    }
}
