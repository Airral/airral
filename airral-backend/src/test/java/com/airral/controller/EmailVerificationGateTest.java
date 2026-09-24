package com.airral.controller;

import com.airral.domain.CandidateNotificationPreference;
import com.airral.domain.User;
import com.airral.dto.request.UpdateNotificationPreferencesRequest;
import com.airral.exception.EmailNotVerifiedException;
import com.airral.repository.UserRepository;
import com.airral.security.JwtTokenProvider;
import com.airral.security.LoginThrottle;
import com.airral.security.TokenVersionCache;
import com.airral.service.AccountVerificationService;
import com.airral.service.CandidateEmailService;
import com.airral.service.CompanyVerificationService;
import com.airral.service.FirebaseIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an account that has not proven its address may and may not do.
 *
 * <p>Uses the real AccountVerificationService over a mocked repository, so the
 * rule under test is the one production runs -- "is email_verified true in the
 * database" -- not a stub that answers whatever the test asks.
 */
class EmailVerificationGateTest {

    private static final String BEARER = "Bearer tok";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CandidateEmailService emailService = mock(CandidateEmailService.class);
    private final JwtTokenProvider jwt = mock(JwtTokenProvider.class);
    private AccountVerificationService verification;
    private CandidateNotificationController notifications;

    @BeforeEach
    void setUp() {
        verification = new AccountVerificationService(
                mock(FirebaseIdentityService.class), userRepository, mock(PasswordEncoder.class),
                mock(TokenVersionCache.class), mock(LoginThrottle.class), mock(CompanyVerificationService.class),
                mock(com.airral.service.FirebaseEmailLinkSender.class));
        notifications = new CandidateNotificationController(emailService, jwt, verification, false);
        when(jwt.getEmailFromToken("tok")).thenReturn("amy@example.com");
        when(jwt.getUserIdFromToken("tok")).thenReturn(7L);
        when(emailService.getOrCreatePreferences("amy@example.com"))
                .thenReturn(Mono.just(CandidateNotificationPreference.builder().id(1L).build()));
        when(emailService.savePreferences(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    private void accountIsVerified(boolean verified) {
        when(userRepository.findById(7L)).thenReturn(Mono.just(
                User.builder().id(7L).email("amy@example.com").emailVerified(verified).isActive(true).build()));
    }

    @Test
    @DisplayName("an unverified account cannot turn an email on")
    void unverifiedCannotEnableMail() {
        accountIsVerified(false);
        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setJobAlertEnabled(true);

        StepVerifier.create(notifications.updatePreferences(request, BEARER))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(EmailNotVerifiedException.class);
                    assertThat(((EmailNotVerifiedException) error).getError()).isEqualTo("EMAIL_NOT_VERIFIED");
                })
                .verify();
        verify(emailService, never()).savePreferences(any());
    }

    @Test
    @DisplayName("an unverified account can always turn email off")
    void unverifiedCanStillStopMail() {
        // Refusing this would be worse than the problem the gate solves: nobody
        // must ever be unable to stop mail.
        accountIsVerified(false);
        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setJobAlertEnabled(false);
        request.setWeeklyDigestEnabled(false);

        StepVerifier.create(notifications.updatePreferences(request, BEARER))
                .expectNextCount(1)
                .verifyComplete();
        verify(emailService).savePreferences(any());
    }

    @Test
    @DisplayName("a verified account can turn email on")
    void verifiedCanEnableMail() {
        accountIsVerified(true);
        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest();
        request.setJobAlertEnabled(true);

        StepVerifier.create(notifications.updatePreferences(request, BEARER))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("the gate reads the database, not the session token")
    void gateReadsTheDatabase() {
        // Verified on another device after this session was minted: the token
        // still says unverified, the row says verified, and the row wins.
        accountIsVerified(true);
        StepVerifier.create(verification.requireVerified(7L, "upload a resume")).verifyComplete();

        accountIsVerified(false);
        StepVerifier.create(verification.requireVerified(7L, "upload a resume"))
                .expectError(EmailNotVerifiedException.class)
                .verify();
    }
}
